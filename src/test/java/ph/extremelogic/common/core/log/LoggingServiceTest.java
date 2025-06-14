package ph.extremelogic.common.core.log;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoggingServiceTest {

    @TempDir
    Path tempDir;

    private String testLogFile;
    private ByteArrayOutputStream consoleOutput;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() throws Exception {
        // Reset singleton instance before each test
        resetSingleton();

        // Create temporary log file
        testLogFile = tempDir.resolve("test.log").toString();

        // Capture console output
        consoleOutput = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(consoleOutput));
    }

    @AfterEach
    void tearDown() throws Exception {
        // Reset singleton after each test
        resetSingleton();

        // Restore console output
        System.setOut(originalOut);
    }

    private void resetSingleton() throws Exception {
        Field instanceField = LoggingService.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    @DisplayName("Should initialize LoggingService correctly")
    void testInitialization() {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.DEBUG);
        LoggingService service = LoggingService.getInstance();

        assertTrue(service.isEnabled());
        assertEquals(testLogFile, service.getLogFileName());
        assertEquals(LoggingService.LogLevel.DEBUG, service.getMinimumLevel());
    }

    @Test
    @DisplayName("Should use INFO as default minimum level")
    void testInitializationWithDefaultLevel() {
        LoggingService.init(true, testLogFile);
        LoggingService service = LoggingService.getInstance();

        assertEquals(LoggingService.LogLevel.INFO, service.getMinimumLevel());
    }

    @Test
    @DisplayName("Should throw exception when getting instance before initialization")
    void testGetInstanceBeforeInit() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                LoggingService::getInstance
        );
        assertEquals("LoggingService not initialized. Call init() first.", exception.getMessage());
    }

    @Test
    @DisplayName("Should be thread-safe singleton")
    void testThreadSafeSingleton() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        LoggingService[] instances = new LoggingService[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);
                    instances[index] = LoggingService.getInstance();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // All instances should be the same
        for (int i = 1; i < threadCount; i++) {
            assertSame(instances[0], instances[i]);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Should write log messages to file and console")
    void testLogWriting() throws IOException {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        logger.info("Test message");

        // Check file content
        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        assertEquals(1, lines.size()); // test message
        assertTrue(lines.get(0).contains("INFO") && lines.get(0).contains("TestLogger") && lines.get(0).contains("Test message"));

        // Check console output
        String consoleString = consoleOutput.toString();
        assertTrue(consoleString.contains("Test message"));
    }

    @Test
    @DisplayName("Should respect minimum log level")
    void testLogLevelFiltering() throws IOException {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.WARN);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        logger.debug("Debug message"); // Should be filtered out
        logger.info("Info message");   // Should be filtered out
        logger.warn("Warning message"); // Should be logged
        logger.error("Error message");  // Should be logged

        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        assertEquals(2, lines.size()); // warn + error
        assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("Warning message"));
        assertTrue(lines.get(1).contains("ERROR") && lines.get(1).contains("Error message"));
    }

    @Test
    @DisplayName("Should log exceptions with stack trace")
    void testExceptionLogging() throws IOException {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.ERROR);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        Exception testException = new RuntimeException("Test exception");
        logger.error("Error occurred", testException);

        String fileContent = Files.readString(Path.of(testLogFile));
        assertTrue(fileContent.contains("Error occurred"));
        assertTrue(fileContent.contains("RuntimeException"));
        assertTrue(fileContent.contains("Test exception"));
    }

    @Test
    @DisplayName("Should handle formatted logging")
    void testFormattedLogging() throws IOException {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        logger.info("User %s logged in with ID %d", "john", 123);

        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        assertTrue(lines.get(0).contains("User john logged in with ID 123"));
    }

    @Test
    @DisplayName("Should not log when disabled")
    void testDisabledLogging() throws IOException {
        LoggingService.init(false, testLogFile, LoggingService.LogLevel.INFO);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        logger.info("This should not be logged");

        assertFalse(Files.exists(Path.of(testLogFile)));
        assertEquals("", consoleOutput.toString());
    }

    @Test
    @DisplayName("Should handle logger creation for class and string")
    void testLoggerCreation() {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);

        LoggingService.Logger classLogger = LoggingService.getLogger(LoggingServiceTest.class);
        LoggingService.Logger stringLogger = LoggingService.getLogger("CustomLogger");

        assertNotNull(classLogger);
        assertNotNull(stringLogger);
    }

    @Test
    @DisplayName("Should check log level enablement correctly")
    void testLogLevelChecks() {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.WARN);
        LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

        assertFalse(logger.isDebugEnabled());
        assertFalse(logger.isInfoEnabled());
        assertTrue(logger.isWarnEnabled());
    }

    @Test
    @DisplayName("Should handle file write errors gracefully")
    void testFileWriteError() {
        // Use an invalid file path
        String invalidPath = "/invalid/path/test.log";
        LoggingService.init(true, invalidPath, LoggingService.LogLevel.INFO);

        // Capture stderr to check error messages
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errorOutput));

        try {
            LoggingService.Logger logger = LoggingService.getLogger("TestLogger");
            logger.info("This should fail to write to file");

            String errorString = errorOutput.toString();
            assertTrue(errorString.contains("Failed to write to log file") ||
                    errorString.contains("Failed to initialize logger"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("Should handle concurrent logging correctly")
    void testConcurrentLogging() throws InterruptedException, IOException {
        LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);

        int threadCount = 10;
        int messagesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    LoggingService.Logger logger = LoggingService.getLogger("Thread-" + threadId);
                    for (int j = 0; j < messagesPerThread; j++) {
                        logger.info("Message %d from thread %d", j, threadId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        // Should have (threadCount * messagesPerThread) messages
        assertEquals((threadCount * messagesPerThread), lines.size());
    }

    @Test
    @DisplayName("Should test LogLevel enum properties")
    void testLogLevelEnum() {
        assertEquals(0, LoggingService.LogLevel.TRACE.getPriority());
        assertEquals(1, LoggingService.LogLevel.DEBUG.getPriority());
        assertEquals(2, LoggingService.LogLevel.INFO.getPriority());
        assertEquals(3, LoggingService.LogLevel.WARN.getPriority());
        assertEquals(4, LoggingService.LogLevel.ERROR.getPriority());

        assertEquals("TRACE", LoggingService.LogLevel.TRACE.getLabel());
        assertEquals("DEBUG", LoggingService.LogLevel.DEBUG.getLabel());
        assertEquals("INFO ", LoggingService.LogLevel.INFO.getLabel());
        assertEquals("WARN ", LoggingService.LogLevel.WARN.getLabel());
        assertEquals("ERROR", LoggingService.LogLevel.ERROR.getLabel());
    }

    @Test
    @DisplayName("Should format log entries correctly")
    void testLogEntryFormat() throws IOException {
        // Mock LocalDateTime to have predictable timestamps
        LocalDateTime fixedTime = LocalDateTime.of(2023, 12, 25, 10, 30, 45, 123000000);

        try (MockedStatic<LocalDateTime> mockedDateTime = Mockito.mockStatic(LocalDateTime.class)) {
            mockedDateTime.when(LocalDateTime::now).thenReturn(fixedTime);

            LoggingService.init(true, testLogFile, LoggingService.LogLevel.INFO);
            LoggingService.Logger logger = LoggingService.getLogger("TestLogger");

            logger.info("Test message");

            List<String> lines = Files.readAllLines(Path.of(testLogFile));
            String logLine = lines.get(0); // Skip initialization message

            assertTrue(logLine.matches("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] INFO  \\[TestLogger\\] - Test message"));
        }
    }
}