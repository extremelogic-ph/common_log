package ph.extremelogic.common.core.log.appender;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.context.ThreadInfo;
import ph.extremelogic.common.core.log.DefaultLogEvent;
import ph.extremelogic.common.core.log.LogEvent;
import ph.extremelogic.common.core.log.message.SimpleMessage;

import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance async appender that wraps other appenders for non-blocking logging.
 * Uses lock-free ring buffer with batching and smart backpressure handling.
 */
public final class AsyncAppender implements Appender {

    // Ring buffer constants - powers of 2 for fast modulo operations
    private static final int DEFAULT_RING_BUFFER_SIZE = 65536; // 64K events
    private static final int MAX_RING_BUFFER_SIZE = 1048576;   // 1M events
    private static final int MIN_RING_BUFFER_SIZE = 1024;     // 1K events

    // Batching and timing constants
    private static final int DEFAULT_BATCH_SIZE = 256;
    private static final int MAX_BATCH_SIZE = 1024;
    private static final long SPIN_TIMEOUT_NANOS = 1000L;     // 1 microsecond
    private static final long PARK_TIMEOUT_NANOS = 100_000L;  // 100 microseconds
    private static final long SHUTDOWN_TIMEOUT_MS = 5000L;    // 5 seconds

    // Configuration
    private final String name;
    private final Appender[] wrappedAppenders;
    private final int ringBufferSize;
    private final int ringBufferMask;
    private final int batchSize;
    private final boolean blockWhenFull;
    private final boolean includeLocation;

    // Ring buffer - lock-free circular buffer
    private final LogEventData[] ringBuffer;
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong readSequence = new AtomicLong(0);

    // Background thread management
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private volatile Thread backgroundThread;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // Performance counters
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong eventsDropped = new AtomicLong(0);
    private final AtomicLong batchesProcessed = new AtomicLong(0);
    private final AtomicLong totalWaitTime = new AtomicLong(0);

    // Reusable batch array to avoid allocations
    private final ThreadLocal<LogEventData[]> batchBuffer =
            ThreadLocal.withInitial(() -> new LogEventData[MAX_BATCH_SIZE]);

    private AsyncAppender(Builder builder) {
        this.name = builder.name;
        this.wrappedAppenders = builder.wrappedAppenders.toArray(new Appender[0]);
        this.ringBufferSize = builder.ringBufferSize;
        this.ringBufferMask = ringBufferSize - 1; // For fast modulo with powers of 2
        this.batchSize = builder.batchSize;
        this.blockWhenFull = builder.blockWhenFull;
        this.includeLocation = builder.includeLocation;

        // Initialize ring buffer
        this.ringBuffer = new LogEventData[ringBufferSize];
        for (int i = 0; i < ringBufferSize; i++) {
            ringBuffer[i] = new LogEventData();
        }
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return; // Already started
        }

        // Start wrapped appenders first
        for (Appender appender : wrappedAppenders) {
            if (!appender.isStarted()) {
                appender.start();
            }
        }

        // Start background processing thread
        backgroundThread = new Thread(this::processEvents, "AsyncAppender-" + name);
        backgroundThread.setDaemon(true);
        backgroundThread.setPriority(Thread.MAX_PRIORITY - 1); // High priority but not max
        backgroundThread.start();
    }

    @Override
    public void stop() {
        if (!started.get() || shutdown.getAndSet(true)) {
            return; // Not started or already shutting down
        }

        // Wait for all pending writes to complete
        long startTime = System.currentTimeMillis();
        while (hasMoreEvents() && (System.currentTimeMillis() - startTime) < SHUTDOWN_TIMEOUT_MS) {
            LockSupport.parkNanos(1_000_000L); // 1ms
        }


        // Signal shutdown to background thread
        if (backgroundThread != null) {
            backgroundThread.interrupt();
        }

        try {
            // Wait for background thread to finish processing
            if (!shutdownLatch.await(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.err.println("AsyncAppender " + name + " shutdown timeout - forcing stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Stop wrapped appenders
        for (Appender appender : wrappedAppenders) {
            appender.stop();
        }

        started.set(false);
    }

    @Override
    public void append(LogEvent event) {
        if (!started.get() && !shutdown.get()) {
            start(); // Auto-start on first use
        }
        if (!started.get() || shutdown.get()) {
            return;
        }

        // Fast path - try to claim a slot in the ring buffer
        long writeSeq = writeSequence.get();
        long readSeq = readSequence.get();

        // Check if buffer is full
        if (writeSeq - readSeq >= ringBufferSize) {
            if (blockWhenFull) {
                // Block until space is available (with timeout)
                long startTime = System.nanoTime();
                while (writeSeq - readSequence.get() >= ringBufferSize) {
                    if (shutdown.get()) return;

                    // Spin briefly, then park
                    if (System.nanoTime() - startTime < SPIN_TIMEOUT_NANOS) {
                        Thread.onSpinWait(); // JDK 9+ hint for busy waiting
                    } else {
                        LockSupport.parkNanos(PARK_TIMEOUT_NANOS);
                        startTime = System.nanoTime(); // Reset spin timer
                    }
                    writeSeq = writeSequence.get();
                }
                totalWaitTime.addAndGet(System.nanoTime() - startTime);
            } else {
                // Drop the event
                eventsDropped.incrementAndGet();
                return;
            }
        }

        // Try to advance write sequence atomically
        if (!writeSequence.compareAndSet(writeSeq, writeSeq + 1)) {
            // Contention - retry with exponential backoff
            append(event);
            return;
        }

        // Copy event data to ring buffer slot
        int index = (int) (writeSeq & ringBufferMask);
        LogEventData slot = ringBuffer[index];
        slot.copyFrom(event, includeLocation);

        // Memory barrier to ensure data is visible before sequence update
        slot.setReady(true);
    }

    /**
     * Background thread processing method - highly optimized event processing loop
     */
    private void processEvents() {
        LogEventData[] batch = batchBuffer.get();

        try {
            while (!shutdown.get() || hasMoreEvents()) {
                int batchCount = collectBatch(batch);

                if (batchCount > 0) {
                    processBatch(batch, batchCount);
                    batchesProcessed.incrementAndGet();
                    eventsProcessed.addAndGet(batchCount);
                } else {
                    // No events available - park briefly to avoid busy waiting
                    LockSupport.parkNanos(PARK_TIMEOUT_NANOS);
                }
            }
        } catch (Exception e) {
            System.err.println("AsyncAppender " + name + " background thread error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Process any remaining events
            while (hasMoreEvents()) {
                int batchCount = collectBatch(batch);
                if (batchCount > 0) {
                    processBatch(batch, batchCount);
                    eventsProcessed.addAndGet(batchCount);
                }
            }
            shutdownLatch.countDown();
        }
    }

    /**
     * Collect a batch of events from the ring buffer
     */
    private int collectBatch(LogEventData[] batch) {
        int count = 0;
        long currentReadSeq = readSequence.get();
        long currentWriteSeq = writeSequence.get();

        while (count < batchSize && currentReadSeq < currentWriteSeq) {
            int index = (int) (currentReadSeq & ringBufferMask);
            LogEventData slot = ringBuffer[index];

            // Wait briefly for slot to be ready instead of breaking
            int retries = 0;
            while (!slot.isReady() && retries < 100) {
                Thread.onSpinWait();
                retries++;
            }

            if (!slot.isReady()) {
                break; // Timeout, try again later
            }

            batch[count++] = slot;
            currentReadSeq++;
        }

        // Atomically update read sequence
        if (count > 0) {
            readSequence.compareAndSet(currentReadSeq - count, currentReadSeq);
        }

        return count;
    }
    /**
     * Process a batch of events by sending to wrapped appenders
     */
    private void processBatch(LogEventData[] batch, int count) {
        // Process all appenders first, then reset ready flags
        for (Appender appender : wrappedAppenders) {
            if (!appender.isStarted()) continue;

            try {
                for (int i = 0; i < count; i++) {
                    LogEventData eventData = batch[i];
                    appender.append(eventData.toLogEvent());
                }
            } catch (Exception e) {
                System.err.println("Error in wrapped appender " + appender.getName() + ": " + e.getMessage());
            }
        }

        // Only reset ready flags after ALL appenders have processed
        for (int i = 0; i < count; i++) {
            batch[i].setReady(false);
        }
    }


    private boolean hasMoreEvents() {
        return readSequence.get() < writeSequence.get();
    }

    @Override
    public boolean isStarted() {
        return started.get() && !shutdown.get();
    }

    @Override
    public String getName() {
        return name;
    }

    // Performance monitoring methods
    public long getEventsProcessed() { return eventsProcessed.get(); }
    public long getEventsDropped() { return eventsDropped.get(); }
    public long getBatchesProcessed() { return batchesProcessed.get(); }
    public long getPendingEvents() { return writeSequence.get() - readSequence.get(); }
    public double getAverageBatchSize() {
        long batches = batchesProcessed.get();
        return batches == 0 ? 0 : (double) eventsProcessed.get() / batches;
    }
    public long getTotalWaitTimeNanos() { return totalWaitTime.get(); }
    public int getRingBufferSize() { return ringBufferSize; }
    public double getRingBufferUtilization() {
        return (double) getPendingEvents() / ringBufferSize * 100.0;
    }

    /**
     * Optimized data holder for log events in the ring buffer
     */
    private static final class LogEventData {
        private long timeMillis;
        private int levelInt;
        private String loggerName;
        private String formattedMessage;
        private String throwableInfo;
        private String threadName;
        private long threadId;
        private boolean threadDaemon;
        private int threadPriority;

        private volatile boolean ready = false;

        void copyFrom(LogEvent event, boolean includeLocation) {
            this.timeMillis = event.getTimeMillis();
            this.levelInt = event.getLevel().getIntLevel();

            if (event instanceof DefaultLogEvent) {
                this.levelInt = ((DefaultLogEvent) event).getLevelInt();
            } else {
                this.levelInt = event.getLevel().getIntLevel();
            }

            this.loggerName = event.getLoggerName();
            this.formattedMessage = event.getMessage().getFormattedMessage();

            ThreadInfo threadInfo = event.getThreadInfo();
            if (threadInfo != null) {
                this.threadName = threadInfo.getName();
                this.threadId = threadInfo.getId();
                this.threadDaemon = threadInfo.isDaemon();
                this.threadPriority = threadInfo.getPriority();
            }

            Throwable thrown = event.getThrown();
            if (thrown != null) {
                this.throwableInfo = thrown.getClass().getName() + ": " + thrown.getMessage();
            } else {
                this.throwableInfo = null;
            }
            // Memory barrier before setting ready flag
            VarHandle.storeStoreFence(); // JDK 9+
            this.ready = true;
        }

        DefaultLogEvent toLogEvent() {
            DefaultLogEvent event = new DefaultLogEvent();

            // Create ThreadInfo with stored values
            ThreadInfo threadInfo = new ThreadInfo(
                    this.threadId,
                    this.threadName,
                    this.threadPriority,
                    this.threadDaemon
            );

            event.reset(loggerName, Level.values()[levelInt],
                    new SimpleMessage(formattedMessage),
                    throwableInfo != null ? new RuntimeException(throwableInfo) : null);

            event.setThreadInfo(threadInfo);

            return event;
        }

        boolean isReady() { return ready; }
        void setReady(boolean ready) { this.ready = ready; }
    }
    /**
     * Builder for AsyncAppender with fluent API
     */
    public static final class Builder {
        private final String name;
        private final java.util.List<Appender> wrappedAppenders = new java.util.ArrayList<>();
        private int ringBufferSize = DEFAULT_RING_BUFFER_SIZE;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private boolean blockWhenFull = false;
        private boolean includeLocation = false;

        public Builder(String name) {
            this.name = name;
        }

        public Builder addAppender(Appender appender) {
            this.wrappedAppenders.add(appender);
            return this;
        }

        public Builder ringBufferSize(int size) {
            // Ensure power of 2 for fast modulo operations
            if (size < MIN_RING_BUFFER_SIZE || size > MAX_RING_BUFFER_SIZE) {
                throw new IllegalArgumentException("Ring buffer size must be between " +
                        MIN_RING_BUFFER_SIZE + " and " + MAX_RING_BUFFER_SIZE);
            }
            if ((size & (size - 1)) != 0) {
                throw new IllegalArgumentException("Ring buffer size must be a power of 2");
            }
            this.ringBufferSize = size;
            return this;
        }

        public Builder batchSize(int size) {
            if (size < 1 || size > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("Batch size must be between 1 and " + MAX_BATCH_SIZE);
            }
            this.batchSize = size;
            return this;
        }

        public Builder blockWhenFull(boolean block) {
            this.blockWhenFull = block;
            return this;
        }

        public Builder includeLocation(boolean include) {
            this.includeLocation = include;
            return this;
        }

        public AsyncAppender build() {
            if (wrappedAppenders.isEmpty()) {
                throw new IllegalStateException("At least one wrapped appender is required");
            }
            return new AsyncAppender(this);
        }
    }
}
