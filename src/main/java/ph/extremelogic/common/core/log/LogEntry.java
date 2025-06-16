package ph.extremelogic.common.core.log;

/**
 * LogEntry class for bulk operations
 */
public class LogEntry {
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
