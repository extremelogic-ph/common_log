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

public class LogManager {

    private static volatile LogManager instance;
    private final boolean enabled;
    protected final LogLevel minimumLevel;
    private final int minimumLevelPriority; // Cache the priority to avoid method call
    private final DateTimeFormatter dateFormatter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Appender> appenders = new ArrayList<>();

    // OPTIMIZATION: Pre-allocate and reuse arrays to avoid ArrayList iteration overhead
    private volatile Appender[] appendersArray = new Appender[0];
    private final Object appendersArrayLock = new Object();

    // Reusable StringBuilder for string formatting (thread-local for thread safety)
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_CACHE =
            ThreadLocal.withInitial(() -> new StringBuilder(256));

    // OPTIMIZATION: Cache system line separator to avoid repeated System.getProperty calls
    private static final String LINE_SEPARATOR = System.lineSeparator();

    // Private constructor to prevent instantiation
    private LogManager(boolean enabled, LogLevel minimumLevel, List<Appender> appenders) {
        this.enabled = enabled;
        this.minimumLevel = minimumLevel;
        this.minimumLevelPriority = minimumLevel.getPriority(); // Cache priority
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.appenders.addAll(appenders);
        updateAppendersArray(); // Initialize the array

        // Initialize logger if service is enabled
        if (enabled) {
            initializeAppenders();
        }
    }

    /**
     * OPTIMIZATION: Update the cached array when appenders change
     */
    private void updateAppendersArray() {
        synchronized (appendersArrayLock) {
            this.appendersArray = appenders.toArray(new Appender[0]);
        }
    }

    /**
     * Initialize all registered appenders
     */
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

    /**
     * Creates a singleton instance of LoggingService with specified appenders
     */
    public static void init(boolean enabled, LogLevel minimumLevel, List<Appender> appenders) {
        if (instance == null) {
            synchronized (LogManager.class) {
                if (instance == null) {
                    instance = new LogManager(enabled, minimumLevel, appenders);
                }
            }
        }
    }

    /**
     * Convenience method to initialize with INFO level and console appender only
     */
    public static void init(boolean enabled) {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new ConsoleAppender());
        init(enabled, LogLevel.INFO, appenders);
    }

    /**
     * Convenience method to initialize with file and console appenders
     */
    public static void init(boolean enabled, String logFileName, LogLevel minimumLevel) {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new ConsoleAppender());
        appenders.add(new FileAppender(logFileName));
        init(enabled, minimumLevel, appenders);
    }

    /**
     * Convenience method to initialize with file and console appenders at INFO level
     */
    public static void init(boolean enabled, String logFileName) {
        init(enabled, logFileName, LogLevel.INFO);
    }

    /**
     * Gets the singleton instance (assumes already initialized)
     */
    public static LogManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("LoggingService not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * Gets a logger for a specific class (SLF4J pattern)
     * OPTIMIZATION: Cache logger instances to avoid repeated object creation
     */
    private static final ConcurrentHashMap<String, Logger> LOGGER_CACHE = new ConcurrentHashMap<>();

    public static Logger getLogger(Class<?> clazz) {
        String name = clazz.getSimpleName();
        return LOGGER_CACHE.computeIfAbsent(name, Logger::new);
    }

    /**
     * Gets a logger for a specific name (SLF4J pattern)
     */
    public static Logger getLogger(String name) {
        return LOGGER_CACHE.computeIfAbsent(name, Logger::new);
    }

    /**
     * HIGHLY OPTIMIZED: Internal method to write log messages
     * Key improvements:
     * - Cached priority comparison (no method call)
     * - Array iteration instead of ArrayList
     * - Lazy log entry with even more optimizations
     * - Lock-free appender array access
     */
    protected void writeLog(LogLevel level, String loggerName, String message) {
        // OPTIMIZATION 1: Early exit with cached priority comparison
        if (!enabled || level.getPriority() < minimumLevelPriority) {
            return;
        }

        // OPTIMIZATION 2: Get snapshot of appenders array (lock-free read)
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return; // No appenders, nothing to do
        }

        // OPTIMIZATION 3: Create optimized lazy log entry
        OptimizedLazyLogEntry lazyEntry = new OptimizedLazyLogEntry(this, level, loggerName, message);

        // OPTIMIZATION 4: Use array iteration (faster than ArrayList iteration)
        for (Appender appender : currentAppenders) {
            try {
                appender.append(lazyEntry.toString());
            } catch (Throwable t) {
                handleAppenderError(appender, t);
            }
        }
    }

    /**
     * HIGHLY OPTIMIZED: Internal method to write log messages with exception
     */
    protected void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        // Early exit with cached priority comparison
        if (!enabled || level.getPriority() < minimumLevelPriority) {
            return;
        }

        // Get snapshot of appenders array (lock-free read)
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        // Create optimized lazy log entry
        OptimizedLazyLogEntry lazyEntry = new OptimizedLazyLogEntry(this, level, loggerName, message);

        // Use array iteration
        for (Appender appender : currentAppenders) {
            try {
                appender.append(lazyEntry.toString(), throwable);
            } catch (Throwable t) {
                handleAppenderError(appender, t);
            }
        }
    }

    /**
     * OPTIMIZATION: Even faster string formatting with reduced method calls
     * and cached line separator
     */
    protected String formatLogEntryFast(LogLevel level, String loggerName, String message) {
        StringBuilder sb = STRING_BUILDER_CACHE.get();
        sb.setLength(0); // Reset the buffer

        // Build: [timestamp] LEVEL [loggerName] - message\n
        sb.append('[');
        sb.append(getFormattedTimestamp());
        sb.append("] ");
        sb.append(level.getLabel());
        sb.append(" [");
        sb.append(loggerName);
        sb.append("] - ");
        sb.append(message);
        sb.append(LINE_SEPARATOR); // Use cached line separator

        return sb.toString();
    }

    /**
     * OPTIMIZATION: Enhanced timestamp caching with longer cache duration
     * Cache timestamp for multiple milliseconds to reduce formatting overhead
     */
    private static final ThreadLocal<EnhancedTimestampCache> TIMESTAMP_CACHE =
            ThreadLocal.withInitial(EnhancedTimestampCache::new);

    private String getFormattedTimestamp() {
        EnhancedTimestampCache cache = TIMESTAMP_CACHE.get();
        long currentTime = System.currentTimeMillis();

        // Cache for up to 10ms to handle burst logging more efficiently
        if (currentTime - cache.lastTimestamp < 10 && cache.formattedTimestamp != null) {
            return cache.formattedTimestamp;
        }

        // Generate new timestamp only when needed
        LocalDateTime now = LocalDateTime.now();
        cache.formattedTimestamp = now.format(dateFormatter);
        cache.lastTimestamp = currentTime;

        return cache.formattedTimestamp;
    }

    /**
     * Enhanced cache for timestamp formatting with longer cache duration
     */
    private static class EnhancedTimestampCache {
        long lastTimestamp = -1;
        String formattedTimestamp = null;
    }

    /**
     * OPTIMIZATION: Ultra-optimized lazy log entry that avoids unnecessary operations
     */
    private static class OptimizedLazyLogEntry {
        private final LogManager logManager;
        private final LogLevel level;
        private final String loggerName;
        private final String message;
        private volatile String formattedMessage; // Cache the formatted result

        OptimizedLazyLogEntry(LogManager logManager, LogLevel level, String loggerName, String message) {
            this.logManager = logManager;
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
        }

        @Override
        public String toString() {
            // Double-checked locking for lazy initialization
            if (formattedMessage == null) {
                synchronized (this) {
                    if (formattedMessage == null) {
                        formattedMessage = logManager.formatLogEntryFast(level, loggerName, message);
                    }
                }
            }
            return formattedMessage;
        }
    }

    /**
     * Handle appender errors without holding locks
     */
    private void handleAppenderError(Appender appender, Throwable t) {
        System.err.println("Failed to append to " + appender.getName());
        t.printStackTrace(System.err);
    }

    /**
     * OPTIMIZATION: Adds a new appender with array update
     */
    public void addAppender(Appender appender) {
        lock.writeLock().lock();
        try {
            try {
                appender.initialize();
                appenders.add(appender);
                updateAppendersArray(); // Update the cached array
            } catch (IOException e) {
                System.err.println("Failed to initialize and add appender " + appender.getName() + ": " + e.getMessage());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * OPTIMIZATION: Removes an appender with array update
     */
    public boolean removeAppender(String appenderName) {
        lock.writeLock().lock();
        try {
            boolean removed = appenders.removeIf(a -> a.getName().equals(appenderName));
            if (removed) {
                updateAppendersArray(); // Update the cached array
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Utility method to check if logging is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the minimum log level
     */
    public LogLevel getMinimumLevel() {
        return minimumLevel;
    }

    /**
     * Gets all registered appenders
     */
    public List<Appender> getAppenders() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(appenders);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * OPTIMIZATION: Bulk logging method for high-throughput scenarios
     * Reduces lock overhead by batching multiple log entries
     */
    public void bulkLog(List<LogEntry> entries) {
        if (!enabled || entries.isEmpty()) {
            return;
        }

        // Filter entries by level first
        List<LogEntry> filteredEntries = entries.stream()
                .filter(entry -> entry.getLevel().getPriority() >= minimumLevelPriority)
                .collect(Collectors.toList());

        if (filteredEntries.isEmpty()) {
            return;
        }

        // Get snapshot of appenders array
        Appender[] currentAppenders = this.appendersArray;
        if (currentAppenders.length == 0) {
            return;
        }

        // Process all entries for each appender
        for (Appender appender : currentAppenders) {
            try {
                for (LogEntry entry : filteredEntries) {
                    String formatted = formatLogEntryFast(entry.getLevel(), entry.getLoggerName(), entry.getMessage());
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

    /**
     * OPTIMIZATION: Non-blocking log level check
     */
    public boolean isLogLevelEnabled(LogLevel level) {
        return enabled && level.getPriority() >= minimumLevelPriority;
    }
}