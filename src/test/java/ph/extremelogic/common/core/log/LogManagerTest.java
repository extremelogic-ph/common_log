package ph.extremelogic.common.core.log;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LogManagerTest {

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
        Field instanceField = LogManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    @DisplayName("Should use INFO as default minimum level")
    void testInitializationWithDefaultLevel() {
        LogManager.init(true, testLogFile);
        LogManager service = LogManager.getInstance();

        assertEquals(LogLevel.INFO, service.getMinimumLevel());
    }

    @Test
    @DisplayName("Should throw exception when getting instance before initialization")
    void testGetInstanceBeforeInit() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                LogManager::getInstance
        );
        assertEquals("LoggingService not initialized. Call init() first.", exception.getMessage());
    }

    @Test
    @DisplayName("Should be thread-safe singleton")
    void testThreadSafeSingleton() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        LogManager[] instances = new LogManager[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    LogManager.init(true, testLogFile, LogLevel.INFO);
                    instances[index] = LogManager.getInstance();
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
        LogManager.init(true, testLogFile, LogLevel.INFO, false);
        Logger logger = LogManager.getLogger("TestLogger");

        logger.info("Test message");
        LogManager.getInstance().flush();

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
        LogManager.init(true, testLogFile, LogLevel.WARN, false);
        Logger logger = LogManager.getLogger("TestLogger");

        logger.debug("Debug message"); // Should be filtered out
        logger.info("Info message");   // Should be filtered out
        logger.warn("Warning message"); // Should be logged
        logger.error("Error message");  // Should be logged

        LogManager.getInstance().flush();

        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        assertEquals(2, lines.size()); // warn + error
        assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("Warning message"));
        assertTrue(lines.get(1).contains("ERROR") && lines.get(1).contains("Error message"));
    }

    @Test
    @DisplayName("Should log exceptions with stack trace")
    void testExceptionLogging() throws IOException {
        LogManager.init(true, testLogFile, LogLevel.ERROR, false);
        Logger logger = LogManager.getLogger("TestLogger");

        Exception testException = new RuntimeException("Test exception");
        logger.error("Error occurred", testException);
        LogManager.getInstance().flush();

        String fileContent = Files.readString(Path.of(testLogFile));
        assertTrue(fileContent.contains("Error occurred"));
        assertTrue(fileContent.contains("RuntimeException"));
        assertTrue(fileContent.contains("Test exception"));
    }

    @Test
    @DisplayName("Should handle formatted logging")
    void testFormattedLogging() throws IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);
        Logger logger = LogManager.getLogger("TestLogger");

        logger.info("User {} logged in with ID {}", "john", 123);
        LogManager.getInstance().flush();

        List<String> lines = Files.readAllLines(Path.of(testLogFile));
        assertTrue(lines.get(0).contains("User john logged in with ID 123"));
    }

    @Test
    @DisplayName("Should not log when disabled")
    void testDisabledLogging() throws IOException {
        LogManager.init(false, testLogFile, LogLevel.INFO);
        Logger logger = LogManager.getLogger("TestLogger");

        logger.info("This should not be logged");

        assertFalse(Files.exists(Path.of(testLogFile)));
        assertEquals("", consoleOutput.toString());
    }

    @Test
    @DisplayName("Should handle logger creation for class and string")
    void testLoggerCreation() {
        LogManager.init(true, testLogFile, LogLevel.INFO);

        Logger classLogger = LogManager.getLogger(LogManagerTest.class);
        Logger stringLogger = LogManager.getLogger("CustomLogger");

        assertNotNull(classLogger);
        assertNotNull(stringLogger);
    }

    @Test
    @DisplayName("Should check log level enablement correctly")
    void testLogLevelChecks() {
        LogManager.init(true, testLogFile, LogLevel.WARN);
        Logger logger = LogManager.getLogger("TestLogger");

        assertFalse(logger.isDebugEnabled());
        assertFalse(logger.isInfoEnabled());
        assertTrue(logger.isWarnEnabled());
    }

    @Test
    @DisplayName("Should handle file write errors gracefully")
    void testFileWriteError() {
        // Use an invalid file path
        String invalidPath = "/invalid/path/test.log";
        LogManager.init(true, invalidPath, LogLevel.INFO, false);

        // Capture stderr to check error messages
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(errorOutput));

        try {
            Logger logger = LogManager.getLogger("TestLogger");
            logger.info("This should fail to write to file");

            String errorString = errorOutput.toString();
            assertTrue(errorString.contains("Failed to append to FILE:/invalid/path/test.log") ||
                    errorString.contains("/invalid/path/test.log (No such file or directory)"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("Should handle concurrent logging correctly")
    void testConcurrentLogging() throws InterruptedException, IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false); // Using sync logging for predictable results

        int threadCount = 10;
        int messagesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    Logger logger = LogManager.getLogger("Thread-" + threadId);
                    for (int j = 0; j < messagesPerThread; j++) {
                        logger.info("Message %d from thread %d", j, threadId);
                    }
                } finally {
                    latch.countDown();
                }
            });
            // Remove the flush() call from here - it's too early!
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Now flush after all logging is done
        LogManager.getInstance().flush();

        // Give a small buffer for any final I/O operations
        Thread.sleep(100);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        List<String> lines = Files.readAllLines(Path.of(testLogFile));

        // Debug: Print actual vs expected
        System.out.println("Expected: " + (threadCount * messagesPerThread));
        System.out.println("Actual: " + lines.size());
        if (lines.size() != (threadCount * messagesPerThread)) {
            System.out.println("First few lines:");
            lines.stream().limit(5).forEach(System.out::println);
            System.out.println("Last few lines:");
            lines.stream().skip(Math.max(0, lines.size() - 5)).forEach(System.out::println);
        }

        // Should have (threadCount * messagesPerThread) messages
        assertEquals((threadCount * messagesPerThread), lines.size());
    }

    @Test
    @DisplayName("Should test LogLevel enum properties")
    void testLogLevelEnum() {
        assertEquals(0, LogLevel.TRACE.getPriority());
        assertEquals(1, LogLevel.DEBUG.getPriority());
        assertEquals(2, LogLevel.INFO.getPriority());
        assertEquals(3, LogLevel.WARN.getPriority());
        assertEquals(4, LogLevel.ERROR.getPriority());

        assertEquals("TRACE", LogLevel.TRACE.getLabel());
        assertEquals("DEBUG", LogLevel.DEBUG.getLabel());
        assertEquals("INFO ", LogLevel.INFO.getLabel());
        assertEquals("WARN ", LogLevel.WARN.getLabel());
        assertEquals("ERROR", LogLevel.ERROR.getLabel());
    }

    @Test
    @DisplayName("Should format log entries correctly")
    void testLogEntryFormat() throws IOException {
        // Mock LocalDateTime to have predictable timestamps
        LocalDateTime fixedTime = LocalDateTime.of(2023, 12, 25, 10, 30, 45, 123000000);

        try (MockedStatic<LocalDateTime> mockedDateTime = Mockito.mockStatic(LocalDateTime.class)) {
            mockedDateTime.when(LocalDateTime::now).thenReturn(fixedTime);

            LogManager.init(true, testLogFile, LogLevel.INFO, false);
            Logger logger = LogManager.getLogger("TestLogger");

            logger.info("Test message");
            LogManager.getInstance().flush();

            List<String> lines = Files.readAllLines(Path.of(testLogFile));
            String logLine = lines.get(0); // Skip initialization message

            assertTrue(logLine.matches("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] INFO \\[TestLogger\\] - Test message"));
        }
    }

    @Test
    @DisplayName("Should handle concurrent logging correctly with async")
    void testConcurrentLoggingAsync() throws InterruptedException, IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, true);

        int threadCount = 10;
        int messagesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    Logger logger = LogManager.getLogger("Thread-" + threadId);
                    for (int j = 0; j < messagesPerThread; j++) {
                        logger.info("Message %d from thread %d", j, threadId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Get the LogManager instance
        LogManager logManager = LogManager.getInstance();

        // Use the enhanced flush method
        logManager.flush();

        // Additional wait for file I/O to complete
        Thread.sleep(500); // Increased from 200ms

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        List<String> lines = Files.readAllLines(Path.of(testLogFile));

        // Debug output
        System.out.println("Expected: " + (threadCount * messagesPerThread));
        System.out.println("Actual: " + lines.size());

        // If we still have issues, print ring buffer stats
        System.out.println("Ring buffer utilization: " + logManager.getRingBufferUtilization());
        System.out.println("Has capacity: " + logManager.hasRingBufferCapacity());

        // Print first few lines to check format
        for (int i = 0; i < Math.min(5, lines.size()); i++) {
            System.out.println("Line " + i + ": " + lines.get(i));
        }

        // Should have (threadCount * messagesPerThread) messages
        assertEquals((threadCount * messagesPerThread), lines.size());
    }

    @Test
    @DisplayName("Should handle high-concurrency logging without data corruption")
    void testHighConcurrencyLogging() throws InterruptedException, IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);

        int threadCount = 50;
        int messagesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);

        Set<String> expectedMessages = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    Logger logger = LogManager.getLogger("Thread-" + threadId);
                    for (int j = 0; j < messagesPerThread; j++) {
                        String message = String.format("Thread-%d-Message-%d", threadId, j);
                        expectedMessages.add(message);
                        logger.info(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // DON'T flush here - threads haven't started yet!
        // LogManager.getInstance().flush(); // REMOVE THIS LINE

        startLatch.countDown(); // Start all threads simultaneously
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS));

        // NOW flush after all logging is complete
        LogManager.getInstance().flush();

        // Give time for final I/O operations
        Thread.sleep(200);

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify all messages were written
        String fileContent = Files.readString(Path.of(testLogFile));

        // Debug information
        System.out.println("Expected messages: " + expectedMessages.size());
        long actualMessageCount = fileContent.lines().count();
        System.out.println("Actual messages in file: " + actualMessageCount);

        for (String expectedMessage : expectedMessages) {
            assertTrue(fileContent.contains(expectedMessage), "Missing message: " + expectedMessage);
        }

        // Also verify the total count matches
        assertEquals(threadCount * messagesPerThread, actualMessageCount);
    }

    // Alternative version with better debugging
    @Test
    @DisplayName("Should handle high-concurrency logging with detailed verification")
    void testHighConcurrencyLoggingWithVerification() throws InterruptedException, IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);

        int threadCount = 50;
        int messagesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);

        // Use a thread-safe map to track what each thread actually logged
        Map<Integer, Set<String>> threadMessages = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threadMessages.put(threadId, ConcurrentHashMap.newKeySet());

            executor.submit(() -> {
                try {
                    startLatch.await();
                    Logger logger = LogManager.getLogger("Thread-" + threadId);

                    for (int j = 0; j < messagesPerThread; j++) {
                        String message = String.format("Thread-%d-Message-%d", threadId, j);
                        threadMessages.get(threadId).add(message);
                        logger.info(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS));

        // Flush and wait
        LogManager.getInstance().flush();
        Thread.sleep(300);

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Read and analyze the file
        List<String> fileLines = Files.readAllLines(Path.of(testLogFile));
        String fileContent = String.join("\n", fileLines);

        System.out.println("=== Test Results ===");
        System.out.println("Expected total messages: " + (threadCount * messagesPerThread));
        System.out.println("Actual lines in file: " + fileLines.size());

        // Check each thread's messages
        Map<Integer, Integer> missingPerThread = new HashMap<>();
        int totalExpected = 0;
        int totalFound = 0;

        for (Map.Entry<Integer, Set<String>> entry : threadMessages.entrySet()) {
            int threadId = entry.getKey();
            Set<String> expectedForThread = entry.getValue();
            totalExpected += expectedForThread.size();

            int foundForThread = 0;
            for (String expectedMessage : expectedForThread) {
                if (fileContent.contains(expectedMessage)) {
                    foundForThread++;
                    totalFound++;
                }
            }

            if (foundForThread != expectedForThread.size()) {
                missingPerThread.put(threadId, expectedForThread.size() - foundForThread);
            }
        }

        System.out.println("Total expected: " + totalExpected);
        System.out.println("Total found: " + totalFound);

        if (!missingPerThread.isEmpty()) {
            System.out.println("Threads with missing messages:");
            missingPerThread.forEach((threadId, missing) ->
                    System.out.println("  Thread " + threadId + ": " + missing + " missing"));
        }

        // Show first few lines for debugging
        System.out.println("First 5 lines in file:");
        fileLines.stream().limit(5).forEach(line -> System.out.println("  " + line));

        // The assertion
        assertEquals(totalExpected, totalFound, "Some messages were not written to file");
    }

    // Simplified version using message counting instead of content verification
    @Test
    @DisplayName("Should handle high-concurrency logging - count verification")
    void testHighConcurrencyLoggingCount() throws InterruptedException, IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);

        int threadCount = 50;
        int messagesPerThread = 100;
        int expectedTotal = threadCount * messagesPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Logger logger = LogManager.getLogger("Thread-" + threadId);
                    for (int j = 0; j < messagesPerThread; j++) {
                        logger.info("Thread-%d-Message-%d", threadId, j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS));

        LogManager.getInstance().flush();
        Thread.sleep(200);

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        long actualCount = Files.lines(Paths.get(testLogFile)).count();

        System.out.println("Expected: " + expectedTotal + ", Actual: " + actualCount);

        assertEquals(expectedTotal, actualCount,
                "Expected " + expectedTotal + " log messages, but found " + actualCount);
    }

    @Test
    @DisplayName("Should maintain acceptable performance under load")
    void testLoggingPerformance() {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);
        Logger logger = LogManager.getLogger("PerformanceTest");

        int messageCount = 10000;
        long startTime = System.nanoTime();

        for (int i = 0; i < messageCount; i++) {
            logger.info("Performance test message %d", i);
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        // Should be able to log 10,000 messages in under 5 seconds
        assertTrue(durationMs < 5000, "Logging took too long: " + durationMs + "ms");

        // Calculate messages per second
        double messagesPerSecond = messageCount / (durationMs / 1000.0);
        System.out.println("Logging performance: " + messagesPerSecond + " messages/second");
    }

    @Test
    @DisplayName("Should handle null and empty parameters gracefully")
    void testNullAndEmptyParameters() {
        LogManager.init(true, testLogFile, LogLevel.INFO);
        Logger logger = LogManager.getLogger("TestLogger");

        // These should not throw exceptions
        assertDoesNotThrow(() -> logger.info(null));
        assertDoesNotThrow(() -> logger.info(""));
        assertDoesNotThrow(() -> logger.info("Test %s", (Object) null));
        assertDoesNotThrow(() -> logger.error("Error", (Throwable) null));
    }

    @Test
    @DisplayName("Should handle very long messages")
    void testLongMessages() throws IOException {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);
        Logger logger = LogManager.getLogger("TestLogger");

        // Create a very long message (10KB)
        String longMessage = "A".repeat(10240);
        logger.info(longMessage);

        LogManager.getInstance().flush();

        String fileContent = Files.readString(Path.of(testLogFile));
        assertTrue(fileContent.contains(longMessage));
    }

    @Test
    @DisplayName("Should not cause memory leaks with many loggers")
    void testMemoryUsageWithManyLoggers() {
        LogManager.init(true, testLogFile, LogLevel.INFO, false);

        // Create many loggers (simulate real application usage)
        List<Logger> loggers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            loggers.add(LogManager.getLogger("Logger-" + i));
        }

        // Force garbage collection and check memory
        System.gc();
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Use the loggers
        loggers.forEach(logger -> logger.info("Test message"));

        // Clear references and force GC
        loggers.clear();
        System.gc();
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Memory usage shouldn't grow significantly
        long memoryDiff = memoryAfter - memoryBefore;
        assertTrue(memoryDiff < 1024 * 1024, "Memory usage grew by " + memoryDiff + " bytes");
    }
}