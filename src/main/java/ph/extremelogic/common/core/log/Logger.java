package ph.extremelogic.common.core.log;

/**
 * Logger class that imitates SLF4J Logger interface
 */
public class Logger {
    private final String name;

    Logger(String name) {
        this.name = name;
    }

    // TRACE level methods
    public void trace(String message) {
        LoggingService.getInstance().writeLog(LogLevel.TRACE, name, message);
    }

    public void trace(String format, Object arg) {
        if (isTraceEnabled()) {
            trace(format(format, arg));
        }
    }

    public void trace(String format, Object arg1, Object arg2) {
        if (isTraceEnabled()) {
            trace(format(format, arg1, arg2));
        }
    }

    public void trace(String format, Object... args) {
        if (isTraceEnabled()) {
            trace(format(format, args));
        }
    }

    public void trace(String message, Throwable t) {
        LoggingService.getInstance().writeLog(LogLevel.TRACE, name, message, t);
    }

    // DEBUG level methods
    public void debug(String message) {
        LoggingService.getInstance().writeLog(LogLevel.DEBUG, name, message);
    }

    public void debug(String format, Object arg) {
        if (isDebugEnabled()) {
            debug(format(format, arg));
        }
    }

    public void debug(String format, Object arg1, Object arg2) {
        if (isDebugEnabled()) {
            debug(format(format, arg1, arg2));
        }
    }

    public void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            debug(format(format, args));
        }
    }

    public void debug(String message, Throwable t) {
        LoggingService.getInstance().writeLog(LogLevel.DEBUG, name, message, t);
    }

    // INFO level methods
    public void info(String message) {
        LoggingService.getInstance().writeLog(LogLevel.INFO, name, message);
    }

    public void info(String format, Object arg) {
        if (isInfoEnabled()) {
            info(format(format, arg));
        }
    }

    public void info(String format, Object arg1, Object arg2) {
        if (isInfoEnabled()) {
            info(format(format, arg1, arg2));
        }
    }

    public void info(String format, Object... args) {
        if (isInfoEnabled()) {
            info(format(format, args));
        }
    }

    public void info(String message, Throwable t) {
        LoggingService.getInstance().writeLog(LogLevel.INFO, name, message, t);
    }

    // WARN level methods
    public void warn(String message) {
        LoggingService.getInstance().writeLog(LogLevel.WARN, name, message);
    }

    public void warn(String format, Object arg) {
        if (isWarnEnabled()) {
            warn(format(format, arg));
        }
    }

    public void warn(String format, Object arg1, Object arg2) {
        if (isWarnEnabled()) {
            warn(format(format, arg1, arg2));
        }
    }

    public void warn(String format, Object... args) {
        if (isWarnEnabled()) {
            warn(format(format, args));
        }
    }

    public void warn(String message, Throwable t) {
        LoggingService.getInstance().writeLog(LogLevel.WARN, name, message, t);
    }

    // ERROR level methods
    public void error(String message) {
        LoggingService.getInstance().writeLog(LogLevel.ERROR, name, message);
    }

    public void error(String format, Object arg) {
        if (isErrorEnabled()) {
            error(format(format, arg));
        }
    }

    public void error(String format, Object arg1, Object arg2) {
        if (isErrorEnabled()) {
            error(format(format, arg1, arg2));
        }
    }

    public void error(String format, Object... args) {
        if (isErrorEnabled()) {
            error(format(format, args));
        }
    }

    public void error(String message, Throwable t) {
        LoggingService.getInstance().writeLog(LogLevel.ERROR, name, message, t);
    }

    // Level check methods
    public boolean isTraceEnabled() {
        return LoggingService.getInstance().minimumLevel.getPriority() <= LogLevel.TRACE.getPriority();
    }

    public boolean isDebugEnabled() {
        return LoggingService.getInstance().minimumLevel.getPriority() <= LogLevel.DEBUG.getPriority();
    }

    public boolean isInfoEnabled() {
        return LoggingService.getInstance().minimumLevel.getPriority() <= LogLevel.INFO.getPriority();
    }

    public boolean isWarnEnabled() {
        return LoggingService.getInstance().minimumLevel.getPriority() <= LogLevel.WARN.getPriority();
    }

    public boolean isErrorEnabled() {
        return LoggingService.getInstance().minimumLevel.getPriority() <= LogLevel.ERROR.getPriority();
    }

    // SLF4J-style parameterized message formatting
    private String format(String format, Object... args) {
        if (format == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int lastIndex = 0;
        int index;

        while ((index = format.indexOf("{}", lastIndex)) != -1 && argIndex < args.length) {
            sb.append(format.substring(lastIndex, index));
            sb.append(args[argIndex] != null ? args[argIndex].toString() : "null");
            argIndex++;
            lastIndex = index + 2;
        }
        sb.append(format.substring(lastIndex));

        // Append remaining arguments if any
        if (argIndex < args.length) {
            sb.append(" [");
            for (; argIndex < args.length; argIndex++) {
                sb.append(args[argIndex] != null ? args[argIndex].toString() : "null");
                if (argIndex < args.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
        }

        return sb.toString();
    }
}
