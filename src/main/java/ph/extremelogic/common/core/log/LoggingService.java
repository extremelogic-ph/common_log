package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;
import ph.extremelogic.common.core.log.appender.FileAppender;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton Logging Service imitating SLF4J/Logback pattern
 * Thread-safe implementation that can be reused across the application
 * Now with support for multiple appenders (console, file, etc.)
 */
public class LoggingService {

    private static volatile LoggingService instance;
    private final boolean enabled;
    protected final LogLevel minimumLevel;
    private final DateTimeFormatter dateFormatter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Appender> appenders = new ArrayList<>();

    // Private constructor to prevent instantiation
    private LoggingService(boolean enabled, LogLevel minimumLevel, List<Appender> appenders) {
        this.enabled = enabled;
        this.minimumLevel = minimumLevel;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.appenders.addAll(appenders);

        // Initialize logger if service is enabled
        if (enabled) {
            initializeAppenders();
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
            synchronized (LoggingService.class) {
                if (instance == null) {
                    instance = new LoggingService(enabled, minimumLevel, appenders);
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
    public static LoggingService getInstance() {
        if (instance == null) {
            throw new IllegalStateException("LoggingService not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * Gets a logger for a specific class (SLF4J pattern)
     */
    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz.getSimpleName());
    }

    /**
     * Gets a logger for a specific name (SLF4J pattern)
     */
    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    /**
     * Internal method to write log messages
     */
    protected void writeLog(LogLevel level, String loggerName, String message) {
        if (!enabled || level.getPriority() < minimumLevel.getPriority()) {
            return;
        }

        String logEntry = formatLogEntry(level, loggerName, message);

        lock.writeLock().lock();
        try {
            for (Appender appender : appenders) {
                try {
                    appender.append(logEntry);
                } catch (Throwable t) {
                    System.err.println("Failed to append to " + appender.getName());
                    t.printStackTrace(System.err);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal method to write log messages with exception
     */
    protected void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (!enabled || level.getPriority() < minimumLevel.getPriority()) {
            return;
        }

        String logEntry = formatLogEntry(level, loggerName, message);

        lock.writeLock().lock();
        try {
            for (Appender appender : appenders) {
                try {
                    appender.append(logEntry, throwable);
                } catch (Throwable t) {
                    System.err.println("Failed to append to " + appender.getName());
                    t.printStackTrace(System.err);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private String formatLogEntry(LogLevel level, String loggerName, String message) {
        String timestamp = LocalDateTime.now().format(dateFormatter);
        return String.format("[%s] %s [%s] - %s%n",
                timestamp, level.getLabel(), loggerName, message);
    }

    /**
     * Adds a new appender to the logging service
     */
    public void addAppender(Appender appender) {
        lock.writeLock().lock();
        try {
            try {
                appender.initialize();
                appenders.add(appender);
            } catch (IOException e) {
                System.err.println("Failed to initialize and add appender " + appender.getName() + ": " + e.getMessage());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes an appender by name
     */
    public boolean removeAppender(String appenderName) {
        lock.writeLock().lock();
        try {
            return appenders.removeIf(a -> a.getName().equals(appenderName));
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
}