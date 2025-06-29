package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;

import java.util.concurrent.atomic.AtomicBoolean;

public final class LogManager {
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean explicitlyInitialized = new AtomicBoolean(false);
    private static LoggerContext context;
    private static Level defaultLevel = Level.INFO;
    private static Configuration defaultConfig;

    public static void setDefaultLevel(Level level) {
        if (!initialized.get()) {
            defaultLevel = level;
        }
    }

    public static LoggerContext getLoggerContext() {
        if (!initialized.get()) {
            initialize(getDefaultConfig());
        }
        return context;
    }

    // Set completely custom default config
    public static void setDefaultConfiguration(Configuration config) {
        if (!initialized.get()) {
            defaultConfig = config;
        }
    }

    private static Configuration getDefaultConfig() {
        if (defaultConfig == null) {
            defaultConfig = new Configuration(defaultLevel);
            if (defaultConfig.getAppenders().isEmpty()) {
                defaultConfig.addAppender(new ConsoleAppender("default-console"));
            }
        }
        return defaultConfig;
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    public static Logger getLogger(String name) {
        if (!initialized.get()) {
            initialize(getDefaultConfig());
        }
        return context.getLogger(name);
    }

    /**
     * Initialize with explicit configuration - this indicates user intent
     */
    public static void initialize(Configuration config) {
        if (initialized.compareAndSet(false, true)) {
            explicitlyInitialized.set(true);
            context = new LoggerContext(config);
            config.start();
            Runtime.getRuntime().addShutdownHook(new Thread(LogManager::shutdown));
        }
    }

    public static void reInitialize(Configuration config) {
        // First, perform cleanup of existing configuration
        if (initialized.get()) {
            try {
                if (context != null) {
                    Configuration oldConfig = context.getConfiguration();
                    if (oldConfig != null) {
                        // Stop all existing appenders
                        for (Appender appender : oldConfig.getAppenders()) {
                            try {
                                if (appender != null && appender.isStarted()) {
                                    appender.stop();
                                }
                            } catch (Exception e) {
                                System.err.printf("Error stopping appender %s during reinitialization: %s%n",
                                        appender != null ? appender.getName() : "null",
                                        e.getMessage());
                            }
                        }
                        oldConfig.stop();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error during cleanup phase of reinitialization: " + e.getMessage());
            }
        }

        // Reset the initialization flags
        initialized.set(false);
        explicitlyInitialized.set(false);
        context = null;

        // Now initialize with the new configuration
        initialize(config);
    }

    /**
     * Check if LogManager was explicitly initialized by user code
     */
    static boolean wasExplicitlyInitialized() {
        return explicitlyInitialized.get();
    }

    public static void shutdown() {
        if (!initialized.get()) {
            return;
        }

        if (initialized.compareAndSet(true, false)) {
            try {
                if (context != null) {
                    Configuration config = context.getConfiguration();
                    if (config != null) {
                        for (Appender appender : config.getAppenders()) {
                            try {
                                if (appender != null) {
                                    if (appender.isStarted()) {
                                        appender.stop();
                                    }
                                }
                            } catch (Exception e) {
                                System.err.printf("Error stopping appender %s: %s%n",
                                        appender != null ? appender.getName() : "null",
                                        e.getMessage());
                            }
                        }
                        config.stop();
                    }
                }
            } catch (Exception e) {
                System.err.println("Critical error during shutdown: " + e.getMessage());
            } finally {
                context = null;
                explicitlyInitialized.set(false);
            }
        }
    }
}