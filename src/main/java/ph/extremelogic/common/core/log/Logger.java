package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;
import ph.extremelogic.common.core.log.message.ParameterizedMessage;

import java.util.concurrent.atomic.AtomicLong;

public final class Logger {
    private static final Object[] EMPTY_PARAMS = new Object[0];

    // Pre-compute level checks to avoid enum comparisons
    private static final int DEBUG_INT = Level.DEBUG.intLevel;
    private static final int INFO_INT = Level.INFO.intLevel;
    private static final int WARN_INT = Level.WARN.intLevel;
    private static final int ERROR_INT = Level.ERROR.intLevel;
    private static final int OFF_INT = Level.OFF.intLevel;

    private final String name;
    private final Configuration configuration;
    private final int minLevelInt;
    private final AtomicLong processedEvents = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final long startTime = System.nanoTime();

    // Cache appenders array to avoid list iteration
    private volatile Appender[] appendersArray;

    private static final ThreadLocal<ParameterizedMessage> MESSAGE_POOL =
            ThreadLocal.withInitial(ParameterizedMessage::new);
    private static final ThreadLocal<DefaultLogEvent> EVENT_POOL =
            ThreadLocal.withInitial(DefaultLogEvent::new);

    public Logger(String name, Configuration configuration) {
        this.name = name;
        this.configuration = configuration;
        this.minLevelInt = configuration.getRootLevel().intLevel;

        // Only add default console appender if this is being used with the default configuration
        // and no appenders were explicitly configured
        if (configuration.getAppenders().isEmpty() && isDefaultConfiguration(configuration)) {
            configuration.addAppender(new ConsoleAppender("console"));
        }

        // Cache appenders as array for faster iteration
        updateAppendersCache();
    }

    public Logger(String name) {
        this(name, LogManager.getLoggerContext().getConfiguration());
    }

    /**
     * Check if this is the default configuration (created automatically by LogManager)
     * vs an explicitly created configuration by the user
     */
    private boolean isDefaultConfiguration(Configuration config) {
        // We can identify the default configuration by checking if LogManager was initialized
        // If LogManager.initialize() was called explicitly, we should respect the user's choice
        return !LogManager.wasExplicitlyInitialized();
    }

    private void updateAppendersCache() {
        this.appendersArray = configuration.getAppenders().toArray(new Appender[0]);
    }

    public boolean isLoggingEnabled() {
        return minLevelInt < OFF_INT;
    }

    public boolean isDebugEnabled() {
        return minLevelInt <= DEBUG_INT;
    }

    public boolean isInfoEnabled() {
        return minLevelInt <= INFO_INT;
    }

    public boolean isWarnEnabled() {
        return minLevelInt <= WARN_INT;
    }

    public boolean isErrorEnabled() {
        return minLevelInt <= ERROR_INT;
    }

    public void info(String message) {
        if (minLevelInt <= INFO_INT) {
            logDirectOptimized(INFO_INT, message, null);
        }
    }

    public void info(String format, Object... args) {
        if (minLevelInt <= INFO_INT) {
            logFormattedOptimized(INFO_INT, format, args);
        }
    }

    public void warn(String message) {
        if (minLevelInt <= WARN_INT) {
            logDirectOptimized(WARN_INT, message, null);
        }
    }

    public void warn(String format, Object... args) {
        if (minLevelInt <= WARN_INT) {
            logFormattedOptimized(WARN_INT, format, args);
        }
    }

    public void error(String message) {
        if (minLevelInt <= ERROR_INT) {
            logDirectOptimized(ERROR_INT, message, null);
        }
    }

    public void error(String message, Throwable t) {
        if (minLevelInt <= ERROR_INT) {
            logDirectOptimized(ERROR_INT, message, t);
        }
    }

    public void error(String format, Object... args) {
        if (minLevelInt <= ERROR_INT) {
            logFormattedOptimized(ERROR_INT, format, args);
        }
    }

    public void debug(String message) {
        if (minLevelInt <= DEBUG_INT) {
            logDirectOptimized(DEBUG_INT, message, null);
        }
    }

    public void debug(String format, Object... args) {
        if (minLevelInt <= DEBUG_INT) {
            logFormattedOptimized(DEBUG_INT, format, args);
        }
    }

    private void logDirectOptimized(int levelInt, String message, Throwable throwable) {
        if (minLevelInt > levelInt) {
            return;
        }

        ParameterizedMessage msg = MESSAGE_POOL.get();
        msg.setMessage(message);
        if (throwable != null) {
            msg.setThrowable(throwable);
        }

        DefaultLogEvent event = EVENT_POOL.get();
        event.resetFastWithLevel(name, levelInt, msg, throwable);
        writeToAppendersOptimized(event);
        msg.clear();
    }

    private void logFormattedOptimized(int levelInt, String format, Object... args) {
        if (minLevelInt > levelInt) {
            return;
        }

        ParameterizedMessage msg = MESSAGE_POOL.get();
        msg.setMessage(format, args != null ? args : EMPTY_PARAMS);

        DefaultLogEvent event = EVENT_POOL.get();
        event.resetFastWithLevel(name, levelInt, msg, null);
        writeToAppendersOptimized(event);
        msg.clear();
    }

    private void writeToAppendersOptimized(LogEvent event) {
        try {
            Appender[] appenders = this.appendersArray;
            for (int i = 0; i < appenders.length; i++) {
                Appender appender = appenders[i];
                if (appender.isStarted()) {
                    appender.append(event);
                }
            }
            processedEvents.incrementAndGet();
        } catch (Exception e) {
            droppedEvents.incrementAndGet();
            e.printStackTrace();
        }
    }

    public long getPendingEvents() { return 0; }
    public long getProcessedEvents() { return processedEvents.get(); }
    public long getDroppedEvents() { return droppedEvents.get(); }
    public double getElapsedTimeSeconds() { return (System.nanoTime() - startTime) / 1_000_000_000.0; }

    public void resetPerformanceCounters() {
        processedEvents.set(0);
        droppedEvents.set(0);
    }

    public void shutdown() {
        Appender[] appenders = this.appendersArray;
        for (int i = 0; i < appenders.length; i++) {
            appenders[i].stop();
        }
    }
}