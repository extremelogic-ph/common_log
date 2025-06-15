package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;
import ph.extremelogic.common.core.log.appender.FileAppender;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import java.util.concurrent.*;

public class LogManager {

    private static volatile LogManager instance;
    private final boolean enabled;
    protected final LogLevel minimumLevel;
    private final int minimumLevelPriority;
    private final DateTimeFormatter dateFormatter;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    private final List<Appender> appenders = new ArrayList<>();

    // ASYNC OPTIMIZATION: Ring buffer for async logging
    private final AsyncLogProcessor asyncProcessor;
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
            this.asyncProcessor = new AsyncLogProcessor();
            this.asyncProcessor.start();
        } else {
            this.asyncProcessor = null;
        }

        if (enabled) {
            initializeAppenders();
        }
    }

    /**
     * ASYNC LOG PROCESSOR - Similar to Log4j2's async architecture
     */
    private class AsyncLogProcessor {
        private final BlockingQueue<LogEvent> eventQueue;
        private final ExecutorService processorThread;
        private volatile boolean running = true;

        // OPTIMIZATION: Batch processing buffer
        private final List<LogEvent> batchBuffer = new ArrayList<>(1000);
        private static final int BATCH_SIZE = 256;
        private static final long BATCH_TIMEOUT_MS = 1;

        AsyncLogProcessor() {
            // Use ArrayBlockingQueue for better performance than LinkedBlockingQueue
            this.eventQueue = new ArrayBlockingQueue<>(8192);
            this.processorThread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AsyncLogProcessor");
                t.setDaemon(true);
                return t;
            });
        }

        void start() {
            processorThread.submit(this::processEvents);
        }

        void shutdown() {
            running = false;
            processorThread.shutdown();
            try {
                if (!processorThread.awaitTermination(5, TimeUnit.SECONDS)) {
                    processorThread.shutdownNow();
                }
            } catch (InterruptedException e) {
                processorThread.shutdownNow();
            }
        }

        boolean tryPublish(LogEvent event) {
            return eventQueue.offer(event);
        }

        void forcePublish(LogEvent event) {
            try {
                eventQueue.put(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Fallback to sync logging
                processSyncLog(event);
            }
        }

        private void processEvents() {
            while (running) {
                try {
                    // OPTIMIZATION: Batch processing like Log4j2
                    batchBuffer.clear();

                    // Wait for first event
                    LogEvent firstEvent = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (firstEvent == null) continue;

                    batchBuffer.add(firstEvent);

                    // Drain additional events up to batch size
                    eventQueue.drainTo(batchBuffer, BATCH_SIZE - 1);

                    // Process the batch
                    processBatch(batchBuffer);

                } catch (InterruptedException e) {
                    if (running) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    System.err.println("Error in async log processor: " + e.getMessage());
                }
            }

            // Process remaining events on shutdown
            batchBuffer.clear();
            eventQueue.drainTo(batchBuffer);
            if (!batchBuffer.isEmpty()) {
                processBatch(batchBuffer);
            }
        }

        private void processBatch(List<LogEvent> events) {
            if (events.isEmpty()) return;

            // Get current appenders snapshot
            Appender[] currentAppenders = appendersArray;
            if (currentAppenders.length == 0) return;

            // OPTIMIZATION: Process all events for each appender (better I/O efficiency)
            for (Appender appender : currentAppenders) {
                try {
                    for (LogEvent event : events) {
                        if (event.throwable != null) {
                            appender.append(event.getFormattedMessage(), event.throwable);
                        } else {
                            appender.append(event.getFormattedMessage());
                        }
                    }
                } catch (Throwable t) {
                    handleAppenderError(appender, t);
                }
            }
        }
    }

    /**
     * OPTIMIZATION: Immutable log event (like Log4j2)
     */
    private static class LogEvent {
        final LogLevel level;
        final String loggerName;
        final String message;
        final Throwable throwable;
        final long timestamp;
        private volatile String formattedMessage; // Lazy formatting

        LogEvent(LogLevel level, String loggerName, String message, Throwable throwable) {
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
            this.throwable = throwable;
            this.timestamp = System.currentTimeMillis();
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

        // Cache for the same millisecond
        if (cache.lastTimestamp == timestamp && cache.formattedTimestamp != null) {
            return cache.formattedTimestamp;
        }

        // Format new timestamp
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                java.time.ZoneId.systemDefault()
        );

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        cache.formattedTimestamp = dateTime.format(formatter);
        cache.lastTimestamp = timestamp;

        return cache.formattedTimestamp;
    }

    private static class TimestampCache {
        long lastTimestamp = -1;
        String formattedTimestamp = null;
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
     * ULTRA-OPTIMIZED: Main logging method - async or sync based on configuration
     */
    protected void writeLog(LogLevel level, String loggerName, String message) {
        writeLog(level, loggerName, message, null);
    }

    protected void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        // OPTIMIZATION 1: Early exit with cached priority comparison
        if (!enabled || level.getPriority() < minimumLevelPriority) {
            return;
        }

        // OPTIMIZATION 2: Create immutable log event
        LogEvent event = new LogEvent(level, loggerName, message, throwable);

        if (asyncEnabled && asyncProcessor != null) {
            // ASYNC PATH: Try to publish to queue (non-blocking)
            if (!asyncProcessor.tryPublish(event)) {
                // Queue full - either drop or force (blocking)
                // For high performance, you might choose to drop
                // For reliability, force publish
                asyncProcessor.forcePublish(event);
            }
        } else {
            // SYNC PATH: Process immediately
            processSyncLog(event);
        }
    }

    /**
     * Synchronous logging fallback
     */
    private void processSyncLog(LogEvent event) {
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        String formattedMessage = event.getFormattedMessage();

        for (Appender appender : currentAppenders) {
            try {
                if (event.throwable != null) {
                    appender.append(formattedMessage, event.throwable);
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
            // Convert to LogEvents and publish
            for (LogEntry entry : entries) {
                if (entry.getLevel().getPriority() >= minimumLevelPriority) {
                    LogEvent event = new LogEvent(entry.getLevel(), entry.getLoggerName(),
                            entry.getMessage(), entry.getThrowable());
                    asyncProcessor.tryPublish(event);
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
                        LogEvent event = new LogEvent(entry.getLevel(), entry.getLoggerName(),
                                entry.getMessage(), entry.getThrowable());
                        String formatted = event.getFormattedMessage();
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

    public void waitForAsyncCompletion(long timeoutMs) throws InterruptedException {
        if (asyncProcessor != null) {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (asyncProcessor.eventQueue.isEmpty()) {
                    Thread.sleep(10); // Give processor time to finish current batch
                    if (asyncProcessor.eventQueue.isEmpty()) {
                        return; // Queue is empty and stayed empty
                    }
                }
                Thread.sleep(10);
            }
            throw new InterruptedException("Timeout waiting for async logging completion");
        }
    }
}