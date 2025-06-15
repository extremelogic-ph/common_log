package ph.extremelogic.common.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import ph.extremelogic.common.core.log.appender.Appender;
import java.util.ArrayList;
import java.util.List;

/**
 * Performance test for LogManager to measure logging throughput
 */
public class LogManagerPerformanceTest {

    private static final int TEST_MESSAGE_COUNT = 500_000;
    private static final long MAX_ALLOWED_TIME_MS = 1000; // 1 second max for 500k messages

    @BeforeEach
    void setUp() {
        // Reset singleton instance before each test
        resetLogManagerInstance();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        resetLogManagerInstance();
    }

    @Test
    void testLoggingPerformance_10000Messages_ShouldCompleteWithinTimeLimit() {
        // Arrange
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new TestAppender()); // Use a no-op appender to avoid I/O overhead

        LogManager.init(true, LogLevel.INFO, appenders);
        Logger logger = LogManager.getLogger(LogManagerPerformanceTest.class);

        // Act
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < TEST_MESSAGE_COUNT; i++) {
            logger.info("Test message number " + i + " with some additional content to simulate real logging");
        }

        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        // Assert
        System.out.println("Logged " + TEST_MESSAGE_COUNT + " messages in " + elapsedTime + "ms");
        System.out.println("Average time per message: " + (elapsedTime / (double) TEST_MESSAGE_COUNT) + "ms");
        System.out.println("Messages per second: " + (TEST_MESSAGE_COUNT * 1000.0 / elapsedTime));

        assertTrue(elapsedTime <= MAX_ALLOWED_TIME_MS,
                String.format("Logging %d messages took %dms, which exceeds the maximum allowed time of %dms",
                        TEST_MESSAGE_COUNT, elapsedTime, MAX_ALLOWED_TIME_MS));
    }

    @Test
    void testLoggingPerformanceWithDifferentLevels_ShouldFilterEfficiently() {
        // Test that filtered messages don't cause performance issues
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new TestAppender());

        LogManager.init(true, LogLevel.ERROR, appenders); // Only ERROR and above
        Logger logger = LogManager.getLogger(LogManagerPerformanceTest.class);

        long startTime = System.currentTimeMillis();

        // These should be filtered out and be very fast
        for (int i = 0; i < TEST_MESSAGE_COUNT; i++) {
            logger.debug("Debug message " + i); // Should be filtered
            logger.info("Info message " + i);   // Should be filtered
            logger.warn("Warn message " + i);   // Should be filtered
        }

        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        System.out.println("Filtered " + (TEST_MESSAGE_COUNT * 3) + " messages in " + elapsedTime + "ms");

        // Filtered messages should be extremely fast (under 100ms for 30k filtered messages)
        assertTrue(elapsedTime <= 100,
                String.format("Filtering %d messages took %dms, which is too slow",
                        TEST_MESSAGE_COUNT * 3, elapsedTime));
    }

    @Test
    void testConcurrentLogging_MultipleThreads() throws InterruptedException {
        List<Appender> appenders = new ArrayList<>();
        appenders.add(new TestAppender());

        LogManager.init(true, LogLevel.INFO, appenders);

        int threadCount = 4;
        int messagesPerThread = TEST_MESSAGE_COUNT / threadCount;
        Thread[] threads = new Thread[threadCount];

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                Logger logger = LogManager.getLogger("Thread-" + threadId);
                for (int i = 0; i < messagesPerThread; i++) {
                    logger.info("Thread " + threadId + " message " + i);
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        System.out.println("Concurrent logging (" + threadCount + " threads, " +
                TEST_MESSAGE_COUNT + " total messages) completed in " + elapsedTime + "ms");

        // Allow more time for concurrent access due to lock contention
        assertTrue(elapsedTime <= MAX_ALLOWED_TIME_MS * 2,
                String.format("Concurrent logging took %dms, which exceeds the maximum allowed time of %dms",
                        elapsedTime, MAX_ALLOWED_TIME_MS * 2));
    }

    /**
     * No-op appender for performance testing that doesn't do actual I/O
     */
    private static class TestAppender implements Appender {
        @Override
        public void initialize() {
            // No-op
        }

        @Override
        public void append(String logEntry) {
            // No-op - just consume the message without doing anything
        }

        @Override
        public void append(String logEntry, Throwable throwable) {
            // No-op
        }

        @Override
        public String getName() {
            return "TestAppender";
        }
    }

    /**
     * Helper method to reset the singleton instance using reflection
     * This is needed because the singleton pattern doesn't provide a reset method
     */
    private void resetLogManagerInstance() {
        try {
            java.lang.reflect.Field instanceField = LogManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            // If reflection fails, we can't reset - this might cause test issues
            System.err.println("Warning: Could not reset LogManager singleton: " + e.getMessage());
        }
    }
}
