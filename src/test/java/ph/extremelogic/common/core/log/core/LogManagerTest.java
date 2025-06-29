package ph.extremelogic.common.core.log.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ph.extremelogic.common.core.log.Configuration;
import ph.extremelogic.common.core.log.LogManager;
import ph.extremelogic.common.core.log.Logger;
import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.appender.ConsoleAppender;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class LogManagerTest {

    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;

    @BeforeEach
    public void setUp() {
        // Capture console output for testing
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        // Force reset LogManager state using reflection
        forceResetLogManager();
    }

    @AfterEach
    public void tearDown() {
        // Clean up LogManager first
        LogManager.shutdown();

        // Force reset again to ensure clean state
        forceResetLogManager();

        // Restore original System.out
        System.setOut(originalOut);
    }

    /**
     * Force reset LogManager static state using reflection
     * This ensures each test starts with a clean slate
     */
    private void forceResetLogManager() {
        try {
            // Reset the initialized flag
            Field initializedField = LogManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            AtomicBoolean initialized = (AtomicBoolean) initializedField.get(null);
            initialized.set(false);

            // Clear the context
            Field contextField = LogManager.class.getDeclaredField("context");
            contextField.setAccessible(true);
            contextField.set(null, null);

            // Reset default configuration
            Field defaultConfigField = LogManager.class.getDeclaredField("defaultConfig");
            defaultConfigField.setAccessible(true);
            defaultConfigField.set(null, null);

            // Reset default level
            Field defaultLevelField = LogManager.class.getDeclaredField("defaultLevel");
            defaultLevelField.setAccessible(true);
            defaultLevelField.set(null, Level.INFO);

        } catch (Exception e) {
            System.err.println("Warning: Could not reset LogManager state: " + e.getMessage());
            // Continue with test - this is a best effort cleanup
        }
    }

    @Nested
    @DisplayName("Basic Logging Tests")
    class BasicLoggingTests {
        @Test
        @DisplayName("Should log info message to default console appender")
        void testInfoMessageLoggingUsingDefaultConsoleAppender() {
            // Arrange
            Logger logger = LogManager.getLogger(LogManagerTest.class.getName());

            // Act
            logger.info("This is a message");

            // Assert
            String output = outputStream.toString();

            assertFalse(output.isEmpty(), "Console output should not be empty");
            assertTrue(output.contains("This is a message"),
                    "Output should contain the logged message");
            assertTrue(output.contains("INFO"),
                    "Output should contain INFO level");
            assertTrue(output.contains(LogManagerTest.class.getSimpleName()),
                    "Output should contain logger name");
        }

        @Test
        @DisplayName("Should log info message to configured console appender")
        void testInfoMessageLoggingUsingConfiguredConsoleAppender() {
            // Arrange
            Configuration configuration = new Configuration(Level.INFO);
            configuration.addAppender(new ConsoleAppender("test"));
            LogManager.setDefaultConfiguration(configuration);
            Logger logger = LogManager.getLogger(LogManagerTest.class.getName());

            // Act
            logger.info("This is a message");

            // Assert
            String output = outputStream.toString();

            assertFalse(output.isEmpty(), "Console output should not be empty");
            assertTrue(output.contains("This is a message"),
                    "Output should contain the logged message");
            assertTrue(output.contains("INFO"),
                    "Output should contain INFO level");
            assertTrue(output.contains(LogManagerTest.class.getSimpleName()),
                    "Output should contain logger name");
        }

        @Test
        @DisplayName("Should log multiple messages to console appender")
        void testMultipleMessages() {
            // Arrange
            Logger logger = LogManager.getLogger("test.logger");

            // Act
            logger.info("First message");
            logger.warn("Warning message");
            logger.error("Error message");

            // Assert
            String output = outputStream.toString();

            assertTrue(output.contains("First message"), "Should contain first message");
            assertTrue(output.contains("Warning message"), "Should contain warning message");
            assertTrue(output.contains("Error message"), "Should contain error message");
        }
    }

    @Nested
    @DisplayName("Formatting Tests")
    class FormattingTests {
        @Test
        @DisplayName("Should properly format console output")
        void testConsoleOutputFormat() {
            // Arrange
            Logger logger = LogManager.getLogger("format.test");

            // Act
            logger.info("Test formatting message");

            // Assert
            String output = normalizeLineEndings(outputStream.toString());

            // Should contain timestamp pattern (yyyy-MM-dd HH:mm:ss.SSS)
            assertTrue(output.matches(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"),
                    "Should contain properly formatted timestamp");

            // Should contain level in brackets
            assertTrue(output.contains("[INFO"), "Should contain level marker");

            // Should contain thread information
            assertTrue(output.contains(Thread.currentThread().getName()),
                    "Should contain thread name");
        }

        private String normalizeLineEndings(String input) {
            return input.replaceAll("\\r\\n", "").replaceAll("\\r", "").replaceAll("\\n", "");
        }

        @Test
        @DisplayName("Should handle parameterized messages")
        void testParameterizedMessages() {
            // Arrange
            Logger logger = LogManager.getLogger("param.test");

            // Act
            logger.info("User {} logged in with role {}", "john.doe", "admin");

            // Assert
            String output = outputStream.toString();

            assertTrue(output.contains("User john.doe logged in with role admin"),
                    "Should properly format parameterized message");
        }

        @Test
        @DisplayName("Should handle null in parameterized messages")
        void testNullParameterizedMessages() {
            // Arrange
            Logger logger = LogManager.getLogger("param.test");

            // Act
            logger.info("User {} logged in with role {}", null, "admin");

            // Assert
            String output = outputStream.toString();

            assertTrue(output.contains("User null logged in with role admin"),
                    "Should properly handle null in parameterized message");
        }

        @Test
        @DisplayName("Should handle exception logging")
        void testExceptionLogging() {
            // Arrange
            Logger logger = LogManager.getLogger("exception.test");
            Exception testException = new RuntimeException("Test exception");

            // Act
            logger.error("An error occurred", testException);

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("An error occurred"), "Should contain error message");
            assertTrue(output.contains("RuntimeException"), "Should contain exception type");
            assertTrue(output.contains("Test exception"), "Should contain exception message");
        }
    }

    @Nested
    @DisplayName("Level Filtering Tests")
    class LevelFilteringTests {
        @Test
        @DisplayName("Should handle debug level filtering")
        void testDebugLevelFiltering() {
            // Arrange - Set default level BEFORE getting logger
            LogManager.setDefaultLevel(Level.INFO);

            Logger logger = LogManager.getLogger("debug.test");

            // Act
            logger.debug("This debug message should not appear");
            logger.info("This info message should appear");

            // Assert
            String output = outputStream.toString();

            assertFalse(output.contains("This debug message should not appear"),
                    "DEBUG message should be filtered out");
            assertTrue(output.contains("This info message should appear"),
                    "INFO message should appear");
        }

        @ParameterizedTest
        @EnumSource(value = Level.class, names = {"DEBUG", "INFO", "WARN", "ERROR"})
        @DisplayName("Should respect all log levels")
        void testAllLogLevels(Level level) {
            // Arrange
            LogManager.setDefaultLevel(level);
            Logger logger = LogManager.getLogger("level.test");

            // Act
            logger.debug("DEBUG message");
            logger.info("INFO message");
            logger.warn("WARN message");
            logger.error("ERROR message");

            // Assert
            String output = outputStream.toString();

            // Check messages at or above the set level should appear
            assertTrue(output.contains(level.name() + " message"),
                    "Should contain message at set level: " + level);

            // Check messages below the set level should not appear
            if (level.ordinal() > Level.DEBUG.ordinal()) {
                assertFalse(output.contains("DEBUG message"),
                        "Should not contain DEBUG message when level is " + level);
            }
            // ... similar checks for other levels
        }

        @Test
        @DisplayName("Should not log messages when level is OFF")
        void testLevelOff() {
            // Arrange
            LogManager.setDefaultLevel(Level.OFF);
            Logger logger = LogManager.getLogger("off.test");

            // Act
            logger.error("This should not appear");
            logger.warn("This should not appear");
            logger.info("This should not appear");
            logger.debug("This should not appear");

            // Assert
            String output = outputStream.toString();
            assertEquals("", output, "No output should be generated when level is OFF");
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {
        @Test
        @DisplayName("Should verify appender is started")
        void testAppenderStartedState() {
            // Arrange & Act
            Logger logger = LogManager.getLogger("startup.test");
            Configuration config = LogManager.getLoggerContext().getConfiguration();

            // Assert
            assertFalse(config.getAppenders().isEmpty(), "Should have at least one appender");

            ConsoleAppender consoleAppender = null;
            for (var appender : config.getAppenders()) {
                if (appender instanceof ConsoleAppender) {
                    consoleAppender = (ConsoleAppender) appender;
                    break;
                }
            }

            assertNotNull(consoleAppender, "Should have a console appender");
            assertTrue(consoleAppender.isStarted(), "Console appender should be started");

            // Test that it actually works
            logger.info("Startup test message");
            String output = outputStream.toString();
            assertTrue(output.contains("Startup test message"), "Should log message successfully");
        }

        @Test
        @DisplayName("Should not log messages when shutdown")
        void testPreShutdownMessages() {
            // Arrange
            Logger logger1 = LogManager.getLogger("ClassA");
            Logger logger2 = LogManager.getLogger("ClassB");
            Logger logger3 = LogManager.getLogger("ClassC");

            // Act - before shutdown
            logger1.info("Message from ClassA");
            logger2.info("Message from ClassB");
            logger3.info("Message from ClassC");

            // Global shutdown
            LogManager.shutdown();

            // Act - after shutdown
            logger1.info("This won't appear A");
            logger2.info("This won't appear B");
            logger3.info("This won't appear C");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("Message from ClassA"), "Should contain pre-shutdown message");
            assertTrue(output.contains("Message from ClassB"), "Should contain pre-shutdown message");
            assertTrue(output.contains("Message from ClassC"), "Should contain pre-shutdown message");
            assertFalse(output.contains("This won't appear A"), "Should not contain post-shutdown message");
            assertFalse(output.contains("This won't appear B"), "Should not contain post-shutdown message");
            assertFalse(output.contains("This won't appear C"), "Should not contain post-shutdown message");
        }

        @Test
        @DisplayName("Should not log messages if no appenders are configured")
        void testNoAppendersConfiguration() {
            // Arrange
            Configuration noOpConfig = new Configuration(Level.ERROR);
            // Don't add any appenders = no output anywhere
            LogManager.initialize(noOpConfig);

            Logger logger = LogManager.getLogger("noappender.test");

            // Act
            logger.error("This error should not appear");

            // Assert
            String output = outputStream.toString();
            assertEquals("", output, "No output should be generated without appenders");
        }

        @Test
        @DisplayName("Should not handle configuration changes at runtime")
        void testRuntimeConfigurationChangesNotHandled() {
            // Arrange - initial config
            Configuration initialConfig = new Configuration(Level.INFO);
            initialConfig.addAppender(new ConsoleAppender("initial"));
            LogManager.setDefaultConfiguration(initialConfig);

            Logger logger = LogManager.getLogger("runtime.config.test");
            logger.info("Initial message");

            // Act - change configuration
            Configuration newConfig = new Configuration(Level.DEBUG);
            newConfig.addAppender(new ConsoleAppender("new"));
            LogManager.initialize(newConfig);

            logger.debug("Debug message after config change");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("Initial message"), "Should contain initial message");
            assertFalse(output.contains("Debug message after config change"),
                    "Should contain debug message after level change");
        }

        @Test
        @DisplayName("Should handle configuration changes at runtime")
        void testRuntimeConfigurationChangesHandled() {
            // Arrange - initial config
            Configuration initialConfig = new Configuration(Level.INFO);
            initialConfig.addAppender(new ConsoleAppender("initial"));
            LogManager.setDefaultConfiguration(initialConfig);

            Logger logger = LogManager.getLogger("runtime.config.test");
            logger.info("Initial message");

            // Act - change configuration
            Configuration newConfig = new Configuration(Level.DEBUG);
            newConfig.addAppender(new ConsoleAppender("new"));
            LogManager.reInitialize(newConfig);
            logger = LogManager.getLogger("runtime.config.test");

            logger.debug("Debug message after config change");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("Initial message"), "Should contain initial message");
            assertTrue(output.contains("Debug message after config change"),
                    "Should contain debug message after level change");
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {
        @Test
        @DisplayName("Should handle empty logger name")
        void testEmptyLoggerName() {
            // Arrange
            Logger logger = LogManager.getLogger("");

            // Act
            logger.info("Message from empty-named logger");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("Message from empty-named logger"),
                    "Should handle empty logger name");
        }

        @Test
        @DisplayName("Should handle null logger name")
        void testNullLoggerName() {
            // Arrange
            Logger logger = LogManager.getLogger((String)null);

            // Act
            logger.info("Message from null-named logger");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("Message from null-named logger"),
                    "Should handle null logger name");
        }

        @Test
        @DisplayName("Should handle null messages")
        void testNullMessage() {
            // Arrange
            Logger logger = LogManager.getLogger("null.message.test");

            // Act
            logger.info(null);

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("null"), "Should handle null message");
        }

        @Test
        @DisplayName("Should handle empty messages")
        void testEmptyMessage() {
            // Arrange
            Logger logger = LogManager.getLogger("empty.message.test");

            // Act
            logger.info("");

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("INFO"), "Should handle empty message");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "\t", "\n"})
        @DisplayName("Should handle blank messages")
        void testBlankMessages(String blankMessage) {
            // Arrange
            Logger logger = LogManager.getLogger("blank.message.test");

            // Act
            logger.info(blankMessage);

            // Assert
            String output = outputStream.toString();
            assertTrue(output.contains("INFO"), "Should handle blank message: '" + blankMessage + "'");
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {
        @Test
        @DisplayName("Should handle high volume of log messages")
        void testHighVolumeLogging() {
            // Arrange
            Logger logger = LogManager.getLogger("volume.test");
            int messageCount = 1000;

            // Act
            for (int i = 0; i < messageCount; i++) {
                logger.info("Test message " + i);
            }

            // Assert
            String output = outputStream.toString();
            for (int i = 0; i < messageCount; i++) {
                assertTrue(output.contains("Test message " + i),
                        "Should contain message " + i);
            }
        }

        @Test
        @DisplayName("Should handle concurrent logging")
        void testConcurrentLogging() throws InterruptedException {
            // Arrange
            Logger logger = LogManager.getLogger("concurrent.test");
            int threadCount = 10;
            int messagesPerThread = 100;
            Thread[] threads = new Thread[threadCount];

            // Act
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < messagesPerThread; j++) {
                        logger.info("Thread " + threadId + " message " + j);
                    }
                });
                threads[i].start();
            }

            // Wait for all threads to complete
            for (Thread thread : threads) {
                thread.join();
            }

            // Assert
            String output = outputStream.toString();
            for (int i = 0; i < threadCount; i++) {
                for (int j = 0; j < messagesPerThread; j++) {
                    assertTrue(output.contains("Thread " + i + " message " + j),
                            "Should contain message from thread " + i + " with count " + j);
                }
            }
        }
    }
}