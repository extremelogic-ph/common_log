package ph.extremelogic.common.core.log;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;
import ph.extremelogic.common.core.log.appender.FileAppender;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class LogManager {

    private static volatile LogManager instance;
    private final boolean enabled;
    protected final LogLevel minimumLevel;
    private final int minimumLevelPriority;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    private final List<Appender> appenders = new ArrayList<>();

    // OPTIMIZATION 1: Static immutable formatter - thread-safe and reusable
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final String LINE_SEPARATOR = System.lineSeparator();

    // OPTIMIZATION 2: Enhanced Disruptor with better wait strategy
    private final DisruptorAsyncLogProcessor asyncProcessor;
    private final boolean asyncEnabled;

    // OPTIMIZATION 3: Atomic reference for lock-free appender updates
    private volatile Appender[] appendersArray = new Appender[0];

    // OPTIMIZATION 4: Enhanced thread-local caching
    private static final ThreadLocal<ThreadLocalCache> THREAD_CACHE =
            ThreadLocal.withInitial(ThreadLocalCache::new);

    // OPTIMIZATION 5: Logger instance caching with weak references to prevent memory leaks
    private static final ConcurrentHashMap<String, WeakReference<Logger>> LOGGER_CACHE =
            new ConcurrentHashMap<>();

    // OPTIMIZATION 6: Pre-calculated level strings to avoid enum.name() calls
    private static final String[] LEVEL_STRINGS = new String[LogLevel.values().length];
    static {
        for (LogLevel level : LogLevel.values()) {
            LEVEL_STRINGS[level.ordinal()] = level.name();
        }
    }

    // Private constructor
    private LogManager(boolean enabled, LogLevel minimumLevel, List<Appender> appenders, boolean asyncEnabled) {
        this.enabled = enabled;
        this.minimumLevel = minimumLevel;
        this.minimumLevelPriority = minimumLevel.getPriority();
        this.appenders.addAll(appenders);
        this.asyncEnabled = asyncEnabled;
        updateAppendersArray();

        // Initialize async processor with optimized settings
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
     * ENHANCED THREAD-LOCAL CACHE - Reduces object allocation
     */
    private static class ThreadLocalCache {
        final StringBuilder stringBuilder = new StringBuilder(512); // Larger initial capacity
        final char[] charBuffer = new char[256]; // For faster string operations
        long lastTimestamp = -1;
        String cachedTimestamp = null;

        // OPTIMIZATION: Cache common log format components
        String lastLoggerName = null;
        String cachedLoggerPrefix = null; // Cache "[loggerName] - " part
    }

    /**
     * ULTRA-OPTIMIZED DISRUPTOR PROCESSOR with better batching and wait strategy
     */
    private class DisruptorAsyncLogProcessor {
        private final Disruptor<DisruptorLogEvent> disruptor;
        private final RingBuffer<DisruptorLogEvent> ringBuffer;
        private final ExecutorService executor;

        // OPTIMIZATION: Larger ring buffer and better wait strategy
        private static final int RING_BUFFER_SIZE = 16384; // 16K events (power of 2)

        DisruptorAsyncLogProcessor() {
            // OPTIMIZATION: Custom thread factory with better thread settings
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "OptimizedLogProcessor");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    System.err.println("Log processor thread error: " + ex.getMessage());
                    ex.printStackTrace(System.err);
                });
                return t;
            });

            this.disruptor = new Disruptor<>(
                    DisruptorLogEvent::new,
                    RING_BUFFER_SIZE,
                    executor,
                    ProducerType.MULTI,
                    // OPTIMIZATION: Better wait strategy for lower latency
                    new SleepingWaitStrategy() // Better than BlockingWaitStrategy for most cases
            );

            disruptor.handleEventsWith(new OptimizedLogEventHandler());
            this.ringBuffer = disruptor.getRingBuffer();
        }

        void start() {
            disruptor.start();
        }

        void shutdown() {
            try {
                disruptor.shutdown(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                disruptor.halt();
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        boolean tryPublish(LogLevel level, String loggerName, String message, Throwable throwable) {
            try {
                long sequence = ringBuffer.tryNext();
                try {
                    DisruptorLogEvent event = ringBuffer.get(sequence);
                    event.setData(level, loggerName, message, throwable);
                } finally {
                    ringBuffer.publish(sequence);
                }
                return true;
            } catch (InsufficientCapacityException e) {
                return false;
            }
        }

        void forcePublish(LogLevel level, String loggerName, String message, Throwable throwable) {
            long sequence = ringBuffer.next();
            try {
                DisruptorLogEvent event = ringBuffer.get(sequence);
                event.setData(level, loggerName, message, throwable);
            } finally {
                ringBuffer.publish(sequence);
            }
        }

        /**
         * OPTIMIZED EVENT HANDLER with better batching
         */
        private class OptimizedLogEventHandler implements EventHandler<DisruptorLogEvent> {
            // OPTIMIZATION: Larger batch size for better throughput
            private final List<DisruptorLogEvent> batchBuffer = new ArrayList<>(512);
            private static final int BATCH_SIZE = 512;
            private static final int FORCE_FLUSH_THRESHOLD = 100; // Force flush after this many events

            @Override
            public void onEvent(DisruptorLogEvent event, long sequence, boolean endOfBatch) throws Exception {
                // Create a copy to avoid data corruption
                DisruptorLogEvent eventCopy = event.createCopy();
                batchBuffer.add(eventCopy);

                // OPTIMIZATION: Dynamic batching based on load
                boolean shouldFlush = endOfBatch ||
                        batchBuffer.size() >= BATCH_SIZE ||
                        (batchBuffer.size() >= FORCE_FLUSH_THRESHOLD && System.nanoTime() % 100 == 0);

                if (shouldFlush) {
                    processBatch();
                }
            }

            private void processBatch() {
                if (batchBuffer.isEmpty()) return;

                Appender[] currentAppenders = appendersArray;
                if (currentAppenders.length == 0) {
                    batchBuffer.clear();
                    return;
                }

                try {
                    // OPTIMIZATION: Pre-format all messages in batch
                    String[] formattedMessages = new String[batchBuffer.size()];
                    for (int i = 0; i < batchBuffer.size(); i++) {
                        formattedMessages[i] = batchBuffer.get(i).getFormattedMessage();
                    }

                    // OPTIMIZATION: Process appenders in parallel only if beneficial
                    if (currentAppenders.length == 1) {
                        processSingleAppender(currentAppenders[0], formattedMessages);
                    } else if (currentAppenders.length <= 4) {
                        // For small number of appenders, sequential is often faster
                        for (Appender appender : currentAppenders) {
                            processSingleAppender(appender, formattedMessages);
                        }
                    } else {
                        // Parallel processing for many appenders
                        Arrays.stream(currentAppenders).parallel()
                                .forEach(appender -> processSingleAppender(appender, formattedMessages));
                    }
                } finally {
                    batchBuffer.clear();
                }
            }

            private void processSingleAppender(Appender appender, String[] formattedMessages) {
                try {
                    // OPTIMIZATION: Batch append if appender supports it
                    if (appender instanceof BatchAppender) {
                        ((BatchAppender) appender).appendBatch(formattedMessages,
                                batchBuffer.stream().map(e -> e.throwable).toArray(Throwable[]::new));
                    } else {
                        // Fallback to individual appends
                        for (int i = 0; i < formattedMessages.length; i++) {
                            if (batchBuffer.get(i).throwable != null) {
                                appender.append(formattedMessages[i], batchBuffer.get(i).throwable);
                            } else {
                                appender.append(formattedMessages[i]);
                            }
                        }
                    }
                } catch (Throwable t) {
                    handleAppenderError(appender, t);
                }
            }
        }

        long getRemainingCapacity() {
            return ringBuffer.remainingCapacity();
        }

        boolean hasAvailableCapacity() {
            return ringBuffer.remainingCapacity() > RING_BUFFER_SIZE / 4; // 25% threshold
        }
    }

    /**
     * OPTIMIZED LOG EVENT with better memory usage
     */
    private static class DisruptorLogEvent {
        private LogLevel level;
        private String loggerName;
        private String message;
        private Throwable throwable;
        private long timestamp;
        private volatile String formattedMessage;

        void setData(LogLevel level, String loggerName, String message, Throwable throwable) {
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
            this.throwable = throwable;
            this.timestamp = System.currentTimeMillis();
            this.formattedMessage = null;
        }

        // OPTIMIZATION: More efficient copy method
        DisruptorLogEvent createCopy() {
            DisruptorLogEvent copy = new DisruptorLogEvent();
            copy.level = this.level;
            copy.loggerName = this.loggerName;
            copy.message = this.message;
            copy.throwable = this.throwable;
            copy.timestamp = this.timestamp;
            // Don't copy formatted message - let it be lazy-calculated
            return copy;
        }

        String getFormattedMessage() {
            String result = formattedMessage;
            if (result == null) {
                synchronized (this) {
                    result = formattedMessage;
                    if (result == null) {
                        formattedMessage = result = formatMessageOptimized();
                    }
                }
            }
            return result;
        }

        private String formatMessageOptimized() {
            ThreadLocalCache cache = THREAD_CACHE.get();
            StringBuilder sb = cache.stringBuilder;
            sb.setLength(0);

            // OPTIMIZATION: Cache timestamp formatting
            String timestampStr;
            if (cache.lastTimestamp == timestamp && cache.cachedTimestamp != null) {
                timestampStr = cache.cachedTimestamp;
            } else {
                timestampStr = formatTimestampFast(timestamp);
                cache.lastTimestamp = timestamp;
                cache.cachedTimestamp = timestampStr;
            }

            // OPTIMIZATION: Cache logger name prefix
            String loggerPrefix;
            if (loggerName.equals(cache.lastLoggerName) && cache.cachedLoggerPrefix != null) {
                loggerPrefix = cache.cachedLoggerPrefix;
            } else {
                loggerPrefix = "] " + LEVEL_STRINGS[level.ordinal()] + " [" + loggerName + "] - ";
                cache.lastLoggerName = loggerName;
                cache.cachedLoggerPrefix = loggerPrefix;
            }

            // Build message: [timestamp] + loggerPrefix + message + newline
            sb.append('[').append(timestampStr).append(loggerPrefix)
                    .append(message).append(LINE_SEPARATOR);

            return sb.toString();
        }
    }

    /**
     * ULTRA-FAST timestamp formatting with multiple optimization layers
     */
    private static String formatTimestampFast(long timestamp) {
        try {
            // OPTIMIZATION: Direct conversion avoiding Instant creation
            long epochSecond = timestamp / 1000;
            int nanoAdjustment = (int) ((timestamp % 1000) * 1_000_000);

            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(
                    epochSecond, nanoAdjustment, SYSTEM_ZONE.getRules().getOffset(Instant.ofEpochMilli(timestamp))
            );

            return dateTime.format(TIMESTAMP_FORMATTER);

        } catch (Exception e) {
            // Fallback to simple format
            return String.format("%tF %<tT.%<tL", timestamp);
        }
    }

    /**
     * OPTIMIZATION: Atomic appender array update
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
        init(enabled, minimumLevel, appenders, true);
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
        LogManager result = instance;
        if (result == null) {
            throw new IllegalStateException("LoggingService not initialized. Call init() first.");
        }
        return result;
    }

    /**
     * OPTIMIZATION: Enhanced logger caching with weak references
     */
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    public static Logger getLogger(String name) {
        WeakReference<Logger> ref = LOGGER_CACHE.get(name);
        Logger logger = (ref != null) ? ref.get() : null;

        if (logger == null) {
            logger = new Logger(name);
            LOGGER_CACHE.put(name, new WeakReference<>(logger));
        }

        return logger;
    }

    /**
     * MAIN LOGGING METHOD - Maximum optimization
     */
    protected void writeLog(LogLevel level, String loggerName, String message) {
        writeLog(level, loggerName, message, null);
    }

    protected void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        // OPTIMIZATION: Fastest possible early exit
        if (!enabled || level.getPriority() < minimumLevelPriority) {
            return;
        }

        if (asyncEnabled && asyncProcessor != null) {
            // Try non-blocking publish first
            if (!asyncProcessor.tryPublish(level, loggerName, message, throwable)) {
                // OPTIMIZATION: Smart backpressure handling
                if (asyncProcessor.hasAvailableCapacity()) {
                    // Buffer has some space, force publish
                    asyncProcessor.forcePublish(level, loggerName, message, throwable);
                } else {
                    // Buffer is nearly full, drop message or use sync fallback
                    // For ultra-high performance, consider dropping
                    // For reliability, use sync fallback:
                    processSyncLog(level, loggerName, message, throwable);
                }
            }
        } else {
            processSyncLog(level, loggerName, message, throwable);
        }
    }

    /**
     * OPTIMIZED synchronous logging
     */
    private void processSyncLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        // Create formatted message once
        String formattedMessage = formatMessageSync(level, loggerName, message);

        // OPTIMIZATION: Unroll loop for single appender case
        if (currentAppenders.length == 1) {
            try {
                if (throwable != null) {
                    currentAppenders[0].append(formattedMessage, throwable);
                } else {
                    currentAppenders[0].append(formattedMessage);
                }
            } catch (Throwable t) {
                handleAppenderError(currentAppenders[0], t);
            }
            return;
        }

        // Multiple appenders
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

    private String formatMessageSync(LogLevel level, String loggerName, String message) {
        ThreadLocalCache cache = THREAD_CACHE.get();
        StringBuilder sb = cache.stringBuilder;
        sb.setLength(0);

        long timestamp = System.currentTimeMillis();
        String timestampStr;

        if (cache.lastTimestamp == timestamp && cache.cachedTimestamp != null) {
            timestampStr = cache.cachedTimestamp;
        } else {
            timestampStr = formatTimestampFast(timestamp);
            cache.lastTimestamp = timestamp;
            cache.cachedTimestamp = timestampStr;
        }

        sb.append('[').append(timestampStr).append("] ")
                .append(LEVEL_STRINGS[level.ordinal()]).append(" [")
                .append(loggerName).append("] - ")
                .append(message).append(LINE_SEPARATOR);

        return sb.toString();
    }

    private void handleAppenderError(Appender appender, Throwable t) {
        System.err.println("Failed to append to " + appender.getName() + ": " + t.getMessage());
    }

    // Configuration methods
    public void addAppender(Appender appender) {
        configLock.writeLock().lock();
        try {
            try {
                appender.initialize();
                appenders.add(appender);
                updateAppendersArray();
            } catch (IOException e) {
                System.err.println("Failed to initialize appender " + appender.getName() + ": " + e.getMessage());
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

    // Getters and utility methods
    public boolean isEnabled() { return enabled; }
    public LogLevel getMinimumLevel() { return minimumLevel; }
    public boolean isLogLevelEnabled(LogLevel level) {
        return enabled && level.getPriority() >= minimumLevelPriority;
    }
    public boolean isAsyncEnabled() { return asyncEnabled; }

    public List<Appender> getAppenders() {
        configLock.readLock().lock();
        try {
            return new ArrayList<>(appenders);
        } finally {
            configLock.readLock().unlock();
        }
    }

    public void shutdown() {
        if (asyncProcessor != null) {
            asyncProcessor.shutdown();
        }
    }

    public double getRingBufferUtilization() {
        if (asyncProcessor != null) {
            long remaining = asyncProcessor.getRemainingCapacity();
            return 1.0 - (double) remaining / DisruptorAsyncLogProcessor.RING_BUFFER_SIZE;
        }
        return 0.0;
    }

    public boolean hasRingBufferCapacity() {
        return asyncProcessor == null || asyncProcessor.hasAvailableCapacity();
    }

    /**
     * OPTIMIZATION: Enhanced bulk logging
     */
    public void bulkLog(List<LogEntry> entries) {
        if (!enabled || entries.isEmpty()) {
            return;
        }

        // Pre-filter entries
        List<LogEntry> validEntries = entries.stream()
                .filter(entry -> entry.getLevel().getPriority() >= minimumLevelPriority)
                .collect(Collectors.toList());

        if (validEntries.isEmpty()) {
            return;
        }

        if (asyncEnabled && asyncProcessor != null) {
            for (LogEntry entry : validEntries) {
                asyncProcessor.tryPublish(entry.getLevel(), entry.getLoggerName(),
                        entry.getMessage(), entry.getThrowable());
            }
        } else {
            processBulkSync(validEntries);
        }
    }

    private void processBulkSync(List<LogEntry> entries) {
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        // Pre-format all messages
        String[] formattedMessages = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            formattedMessages[i] = formatMessageSync(entry.getLevel(),
                    entry.getLoggerName(), entry.getMessage());
        }

        // Send to appenders
        for (Appender appender : currentAppenders) {
            try {
                if (appender instanceof BatchAppender) {
                    Throwable[] throwables = entries.stream()
                            .map(LogEntry::getThrowable)
                            .toArray(Throwable[]::new);
                    ((BatchAppender) appender).appendBatch(formattedMessages, throwables);
                } else {
                    for (int i = 0; i < formattedMessages.length; i++) {
                        if (entries.get(i).getThrowable() != null) {
                            appender.append(formattedMessages[i], entries.get(i).getThrowable());
                        } else {
                            appender.append(formattedMessages[i]);
                        }
                    }
                }
            } catch (Throwable t) {
                handleAppenderError(appender, t);
            }
        }
    }

    // LogEntry class for bulk operations
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
     * Interface for appenders that support batch operations
     */
    public interface BatchAppender {
        void appendBatch(String[] messages, Throwable[] throwables) throws IOException;
    }
}