package ph.extremelogic.common.core.log;

import com.lmax.disruptor.TimeoutException;
import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;
import ph.extremelogic.common.core.log.appender.FileAppender;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import java.util.concurrent.*;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class LogManager {

    private static volatile LogManager instance;
    private final boolean enabled;
    protected final LogLevel minimumLevel;
    private final int minimumLevelPriority;
    private final DateTimeFormatter dateFormatter;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    private final List<Appender> appenders = new ArrayList<>();

    // OPTIMIZATION: Cache formatter as well since it's expensive to create
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    // DISRUPTOR OPTIMIZATION: Ring buffer for async logging
    private final DisruptorAsyncLogProcessor asyncProcessor;
    private final boolean asyncEnabled;

    // OPTIMIZATION: Pre-allocate and reuse arrays to avoid ArrayList iteration overhead
    private volatile Appender[] appendersArray = new Appender[0];

    // Reusable StringBuilder for string formatting (thread-local for thread safety)
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_CACHE =
            ThreadLocal.withInitial(() -> new StringBuilder(256));

    // OPTIMIZATION: Cache system line separator
    private static final String LINE_SEPARATOR = System.lineSeparator();

    // OPTIMIZATION: Logger instance caching
    private static final ConcurrentHashMap<String, Logger> LOGGER_CACHE = new ConcurrentHashMap<>();

    // Private constructor
    private LogManager(boolean enabled, LogLevel minimumLevel, List<Appender> appenders, boolean asyncEnabled) {
        this.enabled = enabled;
        this.minimumLevel = minimumLevel;
        this.minimumLevelPriority = minimumLevel.getPriority();
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.appenders.addAll(appenders);
        this.asyncEnabled = asyncEnabled;
        updateAppendersArray();

        // Initialize async processor if enabled
        if (enabled && asyncEnabled) {
            this.asyncProcessor = new DisruptorAsyncLogProcessor();
            this.asyncProcessor.start();
        } else {
            this.asyncProcessor = null;
        }

        if (enabled) {
            initializeAppenders();
        }
    }

    /**
     * DISRUPTOR-BASED ASYNC LOG PROCESSOR - Ultra-high performance with zero locking
     */
    private class DisruptorAsyncLogProcessor {
        private final Disruptor<DisruptorLogEvent> disruptor;
        private final RingBuffer<DisruptorLogEvent> ringBuffer;
        private final EventHandler<DisruptorLogEvent> eventHandler;
        private final ExecutorService executor;

        // Ring buffer size - must be power of 2
        private static final int RING_BUFFER_SIZE = 8192; // 8K events

        DisruptorAsyncLogProcessor() {
            // Create single thread executor for event processing
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DisruptorLogProcessor");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
                return t;
            });

            // Create event handler that processes log events
            this.eventHandler = new LogEventHandler();

            // Create disruptor with factory and event handler
            this.disruptor = new Disruptor<>(
                    DisruptorLogEvent::new,     // Event factory
                    RING_BUFFER_SIZE,           // Ring buffer size
                    executor,                   // Executor
                    ProducerType.MULTI,         // Multiple producers (threads)
                    new BlockingWaitStrategy()  // Wait strategy
            );

            // Set event handler
            this.disruptor.handleEventsWith(eventHandler);

            // Get ring buffer reference
            this.ringBuffer = disruptor.getRingBuffer();
        }

        void start() {
            disruptor.start();
        }

        void shutdown() {
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                disruptor.halt();
            }
            executor.shutdown();
        }

        /**
         * Try to publish event without blocking
         */
        boolean tryPublish(LogLevel level, String loggerName, String message, Throwable throwable) {
            try {
                // Try to get next sequence number (non-blocking)
                long sequence = ringBuffer.tryNext();

                try {
                    DisruptorLogEvent event = ringBuffer.get(sequence);
                    event.setData(level, loggerName, message, throwable);
                } finally {
                    ringBuffer.publish(sequence);
                }
                return true;

            } catch (InsufficientCapacityException e) {
                // Ring buffer is full
                return false;
            }
        }

        /**
         * Force publish event (blocking if necessary)
         */
        void forcePublish(LogLevel level, String loggerName, String message, Throwable throwable) {
            // This will block if ring buffer is full
            long sequence = ringBuffer.next();
            try {
                DisruptorLogEvent event = ringBuffer.get(sequence);
                event.setData(level, loggerName, message, throwable);
            } finally {
                ringBuffer.publish(sequence);
            }
        }

        /**
         * Event handler that processes log events
         */
        private class LogEventHandler implements EventHandler<DisruptorLogEvent> {
            // Batch processing buffer
            private final List<DisruptorLogEvent> batchBuffer = new ArrayList<>(256);
            private static final int BATCH_SIZE = 256;

            @Override
            public void onEvent(DisruptorLogEvent event, long sequence, boolean endOfBatch) throws Exception {
                // Add event to batch
                batchBuffer.add(event.copy()); // Copy to avoid overwrite

                // Process batch when full or at end of batch
                if (batchBuffer.size() >= BATCH_SIZE || endOfBatch) {
                    processBatch(batchBuffer);
                    batchBuffer.clear();
                }
            }

            private void processBatch(List<DisruptorLogEvent> events) {
                if (events.isEmpty()) return;

                // Get current appenders snapshot
                Appender[] currentAppenders = appendersArray;
                if (currentAppenders.length == 0) return;

                // OPTIMIZATION: Process all events for each appender (better I/O efficiency)
                for (Appender appender : currentAppenders) {
                    try {
                        for (DisruptorLogEvent event : events) {
                            String formattedMessage = event.getFormattedMessage();
                            if (event.throwable != null) {
                                appender.append(formattedMessage, event.throwable);
                            } else {
                                appender.append(formattedMessage);
                            }
                        }
                    } catch (Throwable t) {
                        handleAppenderError(appender, t);
                    }
                }
            }
        }

        /**
         * Get ring buffer size for monitoring
         */
        long getRemainingCapacity() {
            return ringBuffer.remainingCapacity();
        }

        /**
         * Check if ring buffer has available capacity
         */
        boolean hasAvailableCapacity() {
            return ringBuffer.remainingCapacity() > 0;
        }
    }

    /**
     * DISRUPTOR LOG EVENT - Mutable and reusable for zero-garbage logging
     */
    private static class DisruptorLogEvent {
        private LogLevel level;
        private String loggerName;
        private String message;
        private Throwable throwable;
        private long timestamp;
        private volatile String formattedMessage; // Lazy formatting

        void setData(LogLevel level, String loggerName, String message, Throwable throwable) {
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
            this.throwable = throwable;
            this.timestamp = System.currentTimeMillis();
            this.formattedMessage = null; // Reset cached formatted message
        }

        /**
         * Create a copy of this event (needed for batch processing)
         */
        DisruptorLogEvent copy() {
            DisruptorLogEvent copy = new DisruptorLogEvent();
            copy.level = this.level;
            copy.loggerName = this.loggerName;
            copy.message = this.message;
            copy.throwable = this.throwable;
            copy.timestamp = this.timestamp;
            copy.formattedMessage = this.formattedMessage;
            return copy;
        }

        String getFormattedMessage() {
            if (formattedMessage == null) {
                synchronized (this) {
                    if (formattedMessage == null) {
                        formattedMessage = formatMessage();
                    }
                }
            }
            return formattedMessage;
        }

        private String formatMessage() {
            StringBuilder sb = STRING_BUILDER_CACHE.get();
            sb.setLength(0);

            // Build: [timestamp] LEVEL [loggerName] - message\n
            sb.append('[');
            sb.append(formatTimestamp(timestamp));
            sb.append("] ");
            sb.append(level.getLabel());
            sb.append(" [");
            sb.append(loggerName);
            sb.append("] - ");
            sb.append(message);
            sb.append(LINE_SEPARATOR);

            return sb.toString();
        }
    }

    /**
     * OPTIMIZATION: Fast timestamp formatting with caching
     */
    private static final ThreadLocal<TimestampCache> TIMESTAMP_CACHE =
            ThreadLocal.withInitial(TimestampCache::new);

    private static String formatTimestamp(long timestamp) {
        TimestampCache cache = TIMESTAMP_CACHE.get();

        // Cache check
        if (cache.lastTimestamp == timestamp && cache.formattedTimestamp != null) {
            return cache.formattedTimestamp;
        }

        try {
            // Create LocalDateTime
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestamp),
                    SYSTEM_ZONE
            );

            // Format using static formatter
            String formatted = dateTime.format(TIMESTAMP_FORMATTER);

            // Cache the result
            cache.formattedTimestamp = formatted;
            cache.lastTimestamp = timestamp;

            return formatted;

        } catch (Exception e) {
            // Fallback: Use simple date format
            try {
                java.util.Date date = new java.util.Date(timestamp);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                return sdf.format(date);
            } catch (Exception fallbackException) {
                // Ultimate fallback
                return java.time.Instant.ofEpochMilli(timestamp).toString();
            }
        }
    }

    // Enhanced TimestampCache with validation
    private static class TimestampCache {
        long lastTimestamp = -1;
        String formattedTimestamp = null;

        // Add validation method
        boolean isValid(long timestamp) {
            return lastTimestamp == timestamp && formattedTimestamp != null;
        }
    }

    /**
     * Update the cached appenders array
     */
    private void updateAppendersArray() {
        this.appendersArray = appenders.toArray(new Appender[0]);
    }

    private synchronized void initializeAppenders() {
        for (Appender appender : appenders) {
            try {
                appender.initialize();
            } catch (Throwable t) {
                System.err.println("Failed to initialize appender " + appender.getName());
                t.printStackTrace(System.err);
            }
        }
    }

    // Initialization methods
    public static void init(boolean enabled, LogLevel minimumLevel, List<Appender> appenders) {
        init(enabled, minimumLevel, appenders, true); // Default to async
    }

    public static void init(boolean enabled, LogLevel minimumLevel, List<Appender> appenders, boolean asyncEnabled) {
        if (instance == null) {
            synchronized (LogManager.class) {
                if (instance == null) {
                    instance = new LogManager(enabled, minimumLevel, appenders, asyncEnabled);
                }
            }
        }
    }

    public static void init(boolean enabled) {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new ConsoleAppender());
        init(enabled, LogLevel.INFO, appenders);
    }

    public static void init(boolean enabled, String logFileName, LogLevel minimumLevel) {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new ConsoleAppender());
        appenders.add(new FileAppender(logFileName));
        init(enabled, minimumLevel, appenders);
    }

    public static void init(boolean enabled, String logFileName, LogLevel minimumLevel, boolean asyncEnabled) {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new ConsoleAppender());
        appenders.add(new FileAppender(logFileName));
        init(enabled, minimumLevel, appenders, asyncEnabled);
    }

    public static void init(boolean enabled, String logFileName) {
        init(enabled, logFileName, LogLevel.INFO);
    }

    public static LogManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("LoggingService not initialized. Call init() first.");
        }
        return instance;
    }

    public static Logger getLogger(Class<?> clazz) {
        String name = clazz.getSimpleName();
        return LOGGER_CACHE.computeIfAbsent(name, Logger::new);
    }

    public static Logger getLogger(String name) {
        return LOGGER_CACHE.computeIfAbsent(name, Logger::new);
    }

    /**
     * ULTRA-OPTIMIZED: Main logging method with Disruptor - zero locking in critical path
     */
    protected void writeLog(LogLevel level, String loggerName, String message) {
        writeLog(level, loggerName, message, null);
    }

    protected void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        // OPTIMIZATION 1: Early exit with cached priority comparison
        if (!enabled || level.getPriority() < minimumLevelPriority) {
            return;
        }

        if (asyncEnabled && asyncProcessor != null) {
            // DISRUPTOR ASYNC PATH: Try to publish to ring buffer (lock-free)
            if (!asyncProcessor.tryPublish(level, loggerName, message, throwable)) {
                // Ring buffer full - either drop or force (blocking)
                // For high performance, you might choose to drop
                // For reliability, force publish
                asyncProcessor.forcePublish(level, loggerName, message, throwable);
            }
        } else {
            // SYNC PATH: Process immediately (fallback)
            processSyncLog(level, loggerName, message, throwable);
        }
    }

    /**
     * Synchronous logging fallback
     */
    private void processSyncLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        // Create temporary event for formatting
        DisruptorLogEvent tempEvent = new DisruptorLogEvent();
        tempEvent.setData(level, loggerName, message, throwable);
        String formattedMessage = tempEvent.getFormattedMessage();

        for (Appender appender : currentAppenders) {
            try {
                if (throwable != null) {
                    appender.append(formattedMessage, throwable);
                } else {
                    appender.append(formattedMessage);
                }
            } catch (Throwable t) {
                handleAppenderError(appender, t);
            }
        }
    }

    private void handleAppenderError(Appender appender, Throwable t) {
        System.err.println("Failed to append to " + appender.getName());
        t.printStackTrace(System.err);
    }

    /**
     * Configuration methods (still need locks for thread safety)
     */
    public void addAppender(Appender appender) {
        configLock.writeLock().lock();
        try {
            try {
                appender.initialize();
                appenders.add(appender);
                updateAppendersArray();
            } catch (IOException e) {
                System.err.println("Failed to initialize and add appender " + appender.getName() + ": " + e.getMessage());
            }
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public boolean removeAppender(String appenderName) {
        configLock.writeLock().lock();
        try {
            boolean removed = appenders.removeIf(a -> a.getName().equals(appenderName));
            if (removed) {
                updateAppendersArray();
            }
            return removed;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LogLevel getMinimumLevel() {
        return minimumLevel;
    }

    public List<Appender> getAppenders() {
        configLock.readLock().lock();
        try {
            return new ArrayList<>(appenders);
        } finally {
            configLock.readLock().unlock();
        }
    }

    public boolean isLogLevelEnabled(LogLevel level) {
        return enabled && level.getPriority() >= minimumLevelPriority;
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    /**
     * Shutdown method to properly close async processor
     */
    public void shutdown() {
        if (asyncProcessor != null) {
            asyncProcessor.shutdown();
        }
    }

    /**
     * OPTIMIZATION: Bulk logging for high-throughput scenarios
     */
    public void bulkLog(List<LogEntry> entries) {
        if (!enabled || entries.isEmpty()) {
            return;
        }

        if (asyncEnabled && asyncProcessor != null) {
            // Convert to individual publications to ring buffer
            for (LogEntry entry : entries) {
                if (entry.getLevel().getPriority() >= minimumLevelPriority) {
                    asyncProcessor.tryPublish(entry.getLevel(), entry.getLoggerName(),
                            entry.getMessage(), entry.getThrowable());
                }
            }
        } else {
            // Sync bulk processing
            List<LogEntry> filteredEntries = entries.stream()
                    .filter(entry -> entry.getLevel().getPriority() >= minimumLevelPriority)
                    .collect(Collectors.toList());

            if (filteredEntries.isEmpty()) {
                return;
            }

            Appender[] currentAppenders = this.appendersArray;
            if (currentAppenders.length == 0) {
                return;
            }

            for (Appender appender : currentAppenders) {
                try {
                    for (LogEntry entry : filteredEntries) {
                        DisruptorLogEvent tempEvent = new DisruptorLogEvent();
                        tempEvent.setData(entry.getLevel(), entry.getLoggerName(),
                                entry.getMessage(), entry.getThrowable());
                        String formatted = tempEvent.getFormattedMessage();
                        if (entry.getThrowable() != null) {
                            appender.append(formatted, entry.getThrowable());
                        } else {
                            appender.append(formatted);
                        }
                    }
                } catch (Throwable t) {
                    handleAppenderError(appender, t);
                }
            }
        }
    }

    /**
     * LogEntry class for bulk operations
     */
    public static class LogEntry {
        private final LogLevel level;
        private final String loggerName;
        private final String message;
        private final Throwable throwable;

        public LogEntry(LogLevel level, String loggerName, String message) {
            this(level, loggerName, message, null);
        }

        public LogEntry(LogLevel level, String loggerName, String message, Throwable throwable) {
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
            this.throwable = throwable;
        }

        public LogLevel getLevel() { return level; }
        public String getLoggerName() { return loggerName; }
        public String getMessage() { return message; }
        public Throwable getThrowable() { return throwable; }
    }

    /**
     * Wait for async processing to complete (for testing/shutdown)
     */
    public void waitForAsyncCompletion(long timeoutMs) throws InterruptedException {
        if (asyncProcessor != null) {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (asyncProcessor.getRemainingCapacity() == DisruptorAsyncLogProcessor.RING_BUFFER_SIZE) {
                    Thread.sleep(10); // Give processor time to finish current batch
                    if (asyncProcessor.getRemainingCapacity() == DisruptorAsyncLogProcessor.RING_BUFFER_SIZE) {
                        return; // Ring buffer is empty and stayed empty
                    }
                }
                Thread.sleep(10);
            }
            throw new InterruptedException("Timeout waiting for async logging completion");
        }
    }

    /**
     * Get ring buffer utilization for monitoring (0.0 = empty, 1.0 = full)
     */
    public double getRingBufferUtilization() {
        if (asyncProcessor != null) {
            long remaining = asyncProcessor.getRemainingCapacity();
            return 1.0 - (double) remaining / DisruptorAsyncLogProcessor.RING_BUFFER_SIZE;
        }
        return 0.0;
    }

    /**
     * Check if ring buffer has available capacity
     */
    public boolean hasRingBufferCapacity() {
        return asyncProcessor == null || asyncProcessor.hasAvailableCapacity();
    }
}