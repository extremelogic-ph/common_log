package ph.extremelogic.common.core.log;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton Logging Service imitating SLF4J/Logback pattern
 * Thread-safe implementation that can be reused across the application
 */
public class LoggingService {

    private static volatile LoggingService instance;
    private final boolean enabled;
    private final String logFileName;
    private final LogLevel minimumLevel;
    private final DateTimeFormatter dateFormatter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean loggerInitialized = false;

    public enum LogLevel {
        TRACE(0, "TRACE"),
        DEBUG(1, "DEBUG"),
        INFO(2, "INFO "),
        WARN(3, "WARN "),
        ERROR(4, "ERROR");

        private final int priority;
        private final String label;

        LogLevel(int priority, String label) {
            this.priority = priority;
            this.label = label;
        }

        public int getPriority() {
            return priority;
        }

        public String getLabel() {
            return label;
        }
    }

    // Private constructor to prevent instantiation
    private LoggingService(boolean enabled, String logFileName, LogLevel minimumLevel) {
        this.enabled = enabled;
        this.logFileName = logFileName;
        this.minimumLevel = minimumLevel;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        // Initialize logger if service is enabled
        if (enabled) {
            initializeLogger();
        }
    }

    /**
     * Initialize the logger by creating/verifying the log file
     */
    private synchronized void initializeLogger() {
        if (!loggerInitialized) {
            try {
                // Test if we can write to the file
                try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
                    writer.print(""); // Just test write access
                }
                loggerInitialized = true;

                // Log initialization message
                writeLog(LogLevel.INFO, "LoggingService", "Logger initialized with file: " + logFileName);
            } catch (IOException e) {
                System.err.println("Failed to initialize logger with file: " + logFileName + " - " + e.getMessage());
            }
        }
    }

    /**
     * Creates a singleton instance of LoggingService
     * Thread-safe double-checked locking pattern
     */
    public static void init(boolean enabled, String logFileName, LogLevel minimumLevel) {
        if (instance == null) {
            synchronized (LoggingService.class) {
                if (instance == null) {
                    instance = new LoggingService(enabled, logFileName, minimumLevel);
                }
            }
        }
    }

    /**
     * Convenience method to initialize with INFO level
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
    private void writeLog(LogLevel level, String loggerName, String message) {
        if (!enabled || level.getPriority() < minimumLevel.getPriority()) {
            return;
        }

        // Ensure logger is initialized before writing
        if (!loggerInitialized) {
            initializeLogger();
        }

        String timestamp = LocalDateTime.now().format(dateFormatter);
        String logEntry = String.format("[%s] %s [%s] - %s%n",
                timestamp, level.getLabel(), loggerName, message);

        lock.writeLock().lock();
        try {
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
                writer.print(logEntry);
            }

            // Also output to console for development
            System.out.print(logEntry);

        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal method to write log messages with exception
     */
    private void writeLog(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (!enabled || level.getPriority() < minimumLevel.getPriority()) {
            return;
        }

        // Ensure logger is initialized before writing
        if (!loggerInitialized) {
            initializeLogger();
        }

        String timestamp = LocalDateTime.now().format(dateFormatter);
        String logEntry = String.format("[%s] %s [%s] - %s%n",
                timestamp, level.getLabel(), loggerName, message);

        lock.writeLock().lock();
        try {
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
                writer.print(logEntry);

                if (throwable != null) {
                    throwable.printStackTrace(writer);
                }
            }

            // Also output to console for development
            System.out.print(logEntry);
            if (throwable != null) {
                throwable.printStackTrace();
            }

        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Logger class that imitates SLF4J Logger interface
     */
    public static class Logger {
        private final String name;

        private Logger(String name) {
            this.name = name;
        }

        public void trace(String message) {
            getInstance().writeLog(LogLevel.TRACE, name, message);
        }

        public void trace(String message, Throwable throwable) {
            getInstance().writeLog(LogLevel.TRACE, name, message, throwable);
        }

        public void debug(String message) {
            getInstance().writeLog(LogLevel.DEBUG, name, message);
        }

        public void debug(String message, Throwable throwable) {
            getInstance().writeLog(LogLevel.DEBUG, name, message, throwable);
        }

        public void info(String message) {
            getInstance().writeLog(LogLevel.INFO, name, message);
        }

        public void info(String message, Throwable throwable) {
            getInstance().writeLog(LogLevel.INFO, name, message, throwable);
        }

        public void warn(String message) {
            getInstance().writeLog(LogLevel.WARN, name, message);
        }

        public void warn(String message, Throwable throwable) {
            getInstance().writeLog(LogLevel.WARN, name, message, throwable);
        }

        public void error(String message) {
            getInstance().writeLog(LogLevel.ERROR, name, message);
        }

        public void error(String message, Throwable throwable) {
            getInstance().writeLog(LogLevel.ERROR, name, message, throwable);
        }

        // Convenience methods for formatted logging (like SLF4J)
        public void info(String format, Object... args) {
            info(String.format(format, args));
        }

        public void debug(String format, Object... args) {
            debug(String.format(format, args));
        }

        public void warn(String format, Object... args) {
            warn(String.format(format, args));
        }

        public void error(String format, Object... args) {
            error(String.format(format, args));
        }

        public boolean isDebugEnabled() {
            return getInstance().minimumLevel.getPriority() <= LogLevel.DEBUG.getPriority();
        }

        public boolean isInfoEnabled() {
            return getInstance().minimumLevel.getPriority() <= LogLevel.INFO.getPriority();
        }

        public boolean isWarnEnabled() {
            return getInstance().minimumLevel.getPriority() <= LogLevel.WARN.getPriority();
        }
    }

    /**
     * Utility method to check if logging is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the current log file name
     */
    public String getLogFileName() {
        return logFileName;
    }

    /**
     * Gets the minimum log level
     */
    public LogLevel getMinimumLevel() {
        return minimumLevel;
    }
}