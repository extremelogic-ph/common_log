package ph.extremelogic.common.core.log;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import ph.extremelogic.common.core.log.appender.Appender;
import ph.extremelogic.common.core.log.appender.BatchAppender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Ultra-optimized Disruptor-based asynchronous log processor.
 * Handles high-throughput logging with minimal latency and memory allocation.
 */
/**
 * ULTRA-OPTIMIZED DISRUPTOR PROCESSOR with better batching and wait strategy
 */
public class DisruptorAsyncLogProcessor {
    private final Disruptor<LogManager.DisruptorLogEvent> disruptor;
    private final RingBuffer<LogManager.DisruptorLogEvent> ringBuffer;
    private final ExecutorService executor;
    private final AtomicLong publishedEvents = new AtomicLong(0);
    private final AtomicLong processedEvents = new AtomicLong(0);

    private final AtomicBoolean processorShutdown = new AtomicBoolean(false);

    // OPTIMIZATION: Larger ring buffer and better wait strategy
    protected static final int RING_BUFFER_SIZE = 16384; // 16K events (power of 2)

    DisruptorAsyncLogProcessor() {
        // OPTIMIZATION: Custom thread factory with better thread settings
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OptimizedLogProcessor");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                System.err.println("Log processor thread error: " + ex.getMessage());
                ex.printStackTrace(System.err);
            });
            return t;
        });

        this.disruptor = new Disruptor<>(
                LogManager.DisruptorLogEvent::new,
                RING_BUFFER_SIZE,
                executor,
                ProducerType.MULTI,
                // OPTIMIZATION: Better wait strategy for lower latency
                new SleepingWaitStrategy() // Better than BlockingWaitStrategy for most cases
        );

        disruptor.handleEventsWith(new OptimizedLogEventHandler());
        this.ringBuffer = disruptor.getRingBuffer();
    }

    void start() {
        disruptor.start();
    }

    void shutdown() {
        if (!processorShutdown.compareAndSet(false, true)) {
            return; // Already shutdown
        }

        try {
            System.out.println("Disruptor shutdown starting...");

            // Try graceful shutdown with timeout
            try {
                disruptor.shutdown(3, TimeUnit.SECONDS);
                System.out.println("Disruptor shutdown completed gracefully.");
            } catch (TimeoutException e) {
                System.out.println("Graceful shutdown timed out, forcing halt...");
                disruptor.halt();
            } catch (Exception e) {
                System.err.println("Error during graceful shutdown, forcing halt: " + e.getMessage());
                disruptor.halt();
            }

        } catch (Exception e) {
            System.err.println("Error during disruptor shutdown: " + e.getMessage());
            disruptor.halt(); // Force stop
        } finally {
            shutdownExecutor();
        }
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.out.println("Executor didn't terminate gracefully, forcing shutdown...");
                executor.shutdownNow();

                // Wait a bit more for forced shutdown
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean tryPublish(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (processorShutdown.get()) {
            return false;
        }

        try {
            long sequence = ringBuffer.tryNext();
            try {
                LogManager.DisruptorLogEvent event = ringBuffer.get(sequence);
                event.setData(level, loggerName, message, throwable);
                publishedEvents.incrementAndGet(); // Track published events
            } finally {
                ringBuffer.publish(sequence);
            }
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    // Modify forcePublish method:
    void forcePublish(LogLevel level, String loggerName, String message, Throwable throwable) {
        if (processorShutdown.get()) {
            return;
        }

        long sequence = ringBuffer.next();
        try {
            LogManager.DisruptorLogEvent event = ringBuffer.get(sequence);
            event.setData(level, loggerName, message, throwable);
            publishedEvents.incrementAndGet(); // Track published events
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    /**
     * OPTIMIZED EVENT HANDLER with better batching
     */
    private class OptimizedLogEventHandler implements EventHandler<LogManager.DisruptorLogEvent> {
        // OPTIMIZATION: Larger batch size for better throughput
        private final List<LogManager.DisruptorLogEvent> batchBuffer = new ArrayList<>(512);
        private static final int BATCH_SIZE = 512;
        private static final int FORCE_FLUSH_THRESHOLD = 100; // Force flush after this many events

        @Override
        public void onEvent(LogManager.DisruptorLogEvent event, long sequence, boolean endOfBatch) throws Exception {
            // Create a copy to avoid data corruption
            LogManager.DisruptorLogEvent eventCopy = event.createCopy();
            batchBuffer.add(eventCopy);

            // OPTIMIZATION: Dynamic batching based on load
            boolean shouldFlush = endOfBatch ||
                    batchBuffer.size() >= BATCH_SIZE ||
                    (batchBuffer.size() >= FORCE_FLUSH_THRESHOLD && System.nanoTime() % 100 == 0);

            if (shouldFlush) {
                processBatch();
            }
        }

        private void processBatch() {
            if (batchBuffer.isEmpty()) return;

            Appender[] currentAppenders = LogManager.getInstance().appendersArray;
            if (currentAppenders.length == 0) {
                // Still count these as processed even if no appenders
                processedEvents.addAndGet(batchBuffer.size());
                batchBuffer.clear();
                return;
            }

            try {
                // OPTIMIZATION: Pre-format all messages in batch
                String[] formattedMessages = new String[batchBuffer.size()];
                for (int i = 0; i < batchBuffer.size(); i++) {
                    formattedMessages[i] = batchBuffer.get(i).getFormattedMessage();
                }

                // OPTIMIZATION: Process appenders in parallel only if beneficial
                if (currentAppenders.length == 1) {
                    processSingleAppender(currentAppenders[0], formattedMessages);
                } else if (currentAppenders.length <= 4) {
                    // For small number of appenders, sequential is often faster
                    for (Appender appender : currentAppenders) {
                        processSingleAppender(appender, formattedMessages);
                    }
                } else {
                    // Parallel processing for many appenders
                    Arrays.stream(currentAppenders).parallel()
                            .forEach(appender -> processSingleAppender(appender, formattedMessages));
                }
                processedEvents.addAndGet(batchBuffer.size());
            } finally {
                batchBuffer.clear();
            }
        }

        private void processSingleAppender(Appender appender, String[] formattedMessages) {
            try {
                // OPTIMIZATION: Batch append if appender supports it
                if (appender instanceof BatchAppender) {
                    ((BatchAppender) appender).appendBatch(formattedMessages,
                            batchBuffer.stream().map(e -> e.throwable).toArray(Throwable[]::new));
                } else {
                    // Fallback to individual appends
                    for (int i = 0; i < formattedMessages.length; i++) {
                        if (batchBuffer.get(i).throwable != null) {
                            appender.append(formattedMessages[i], batchBuffer.get(i).throwable);
                        } else {
                            appender.append(formattedMessages[i]);
                        }
                    }
                }
            } catch (Throwable t) {
                LogManager.getInstance().handleAppenderError(appender, t);
            }
        }
    }

    long getRemainingCapacity() {
        return ringBuffer.remainingCapacity();
    }

    boolean hasAvailableCapacity() {
        return ringBuffer.remainingCapacity() > RING_BUFFER_SIZE / 4; // 25% threshold
    }
    boolean isProcessingComplete() {
        return publishedEvents.get() == processedEvents.get();
    }

    long getPendingEventCount() {
        return publishedEvents.get() - processedEvents.get();
    }
}
