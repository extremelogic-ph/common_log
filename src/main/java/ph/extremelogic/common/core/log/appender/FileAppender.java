package ph.extremelogic.common.core.log.appender;

import ph.extremelogic.common.core.log.LogEvent;
import ph.extremelogic.common.core.log.layout.Layout;
import ph.extremelogic.common.core.log.layout.PatternLayout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class FileAppender implements Appender {
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024; // 64KB buffer
    private static final int MAX_BATCH_SIZE = 16 * 1024; // 16KB batch writes
    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final byte[] LINE_SEPARATOR_BYTES = LINE_SEPARATOR.getBytes(StandardCharsets.UTF_8);

    private final String name;
    private final String fileName;
    private final Layout<String> layout;
    private final boolean append;
    private final boolean immediateFlush;
    private final long maxFileSize;
    private final int maxBackups;
    private final int bufferSize;

    private volatile boolean shutdownInProgress = false;

    // File I/O components
    private volatile FileChannel fileChannel;
    private volatile ByteBuffer writeBuffer;
    private final ReentrantReadWriteLock channelLock = new ReentrantReadWriteLock();

    // Performance tracking
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong writeOperations = new AtomicLong(0);
    private final AtomicLong flushOperations = new AtomicLong(0);

    // Thread-local byte array pool for encoding
    private static final ThreadLocal<byte[]> ENCODE_BUFFER_POOL =
            ThreadLocal.withInitial(() -> new byte[8192]);

    private volatile boolean started = false;
    private volatile Path currentFilePath;

    public FileAppender(String name, String fileName) {
        this(name, fileName, new PatternLayout(), true, true,
                100 * 1024 * 1024L, 5, DEFAULT_BUFFER_SIZE); // 100MB default
    }

    public FileAppender(String name, String fileName, Layout<String> layout,
                        boolean append, boolean immediateFlush,
                        long maxFileSize, int maxBackups, int bufferSize) {
        this.name = name;
        this.fileName = fileName;
        this.layout = layout != null ? layout : new PatternLayout();
        this.append = append;
        this.immediateFlush = immediateFlush;
        this.maxFileSize = maxFileSize;
        this.maxBackups = maxBackups;
        this.bufferSize = Math.max(bufferSize, 4096); // Minimum 4KB
    }

    @Override
    public void start() {
        if (started) return;

        channelLock.writeLock().lock();
        try {
            if (started) return; // Double-check

            currentFilePath = Paths.get(fileName);
            createDirectoriesIfNeeded();
            openFileChannel();

            started = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to start FileAppender: " + name, e);
        } finally {
            channelLock.writeLock().unlock();
        }
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        channelLock.writeLock().lock();
        try {
            // Double-check in case another thread beat us to it
            if (!started) {
                return;
            }
            started = false;

            // 1. First try to flush remaining data if channel is still open
            if (writeBuffer != null && writeBuffer.position() > 0) {
                if (fileChannel != null && fileChannel.isOpen()) {
                    try {
                        flushBuffer();
                        System.out.printf("Successfully flushed %d bytes before shutdown%n",
                                writeBuffer.position());
                    } catch (IOException e) {
                        System.err.printf("Error flushing buffer for %s: %s%n",
                                name, e.getMessage());
                    }
                } else {
                    System.out.printf("Skipping flush for %s - channel %s%n",
                            name,
                            fileChannel == null ? "is null" : "already closed");
                }
            }

            // 2. Then close resources
            closeFileChannel();
        } finally {
            writeBuffer = null;
            channelLock.writeLock().unlock();
        }
    }

    private void closeFileChannel() {
        if (fileChannel != null) {
            try {
                if (fileChannel.isOpen()) {
                    try {
                        // Force any OS-level buffered writes
                        fileChannel.force(true);
                        fileChannel.close();
                        System.out.printf("Successfully closed file channel for %s%n", name);
                    } catch (IOException e) {
                        System.err.printf("Error forcing data to disk for %s: %s%n",
                                name, e.getMessage());
                    }
                }
            } finally {
                fileChannel = null;
            }
        }
    }

    @Override
    public void append(LogEvent event) {
        if (!started) return;

        try {
            String message = layout.toSerializable(event);
            writeMessage(message);
            writeOperations.incrementAndGet();

        } catch (Exception e) {
            // Log to stderr to avoid infinite recursion
            System.err.println("FileAppender " + name + " failed to write: " + e.getMessage());
        }
    }

    private void writeMessage(String message) throws IOException {
        // Get thread-local encoding buffer
        byte[] encodeBuffer = ENCODE_BUFFER_POOL.get();

        // Convert message to bytes efficiently
        byte[] messageBytes = encodeToBytes(message, encodeBuffer);

        channelLock.readLock().lock();
        try {
            if (!started || fileChannel == null || !fileChannel.isOpen()) {
                return;
            }

            // Check if we need to rotate the file
            checkAndRotateFile();

            // Write to buffer
            if (writeBuffer.remaining() < messageBytes.length + LINE_SEPARATOR_BYTES.length) {
                flushBuffer();
            }

            writeBuffer.put(messageBytes);
            writeBuffer.put(LINE_SEPARATOR_BYTES);

            bytesWritten.addAndGet(messageBytes.length + LINE_SEPARATOR_BYTES.length);

            // Immediate flush if configured or buffer is getting full
            if (immediateFlush || writeBuffer.remaining() < MAX_BATCH_SIZE) {
                flushBuffer();
            }

        } finally {
            channelLock.readLock().unlock();
        }
    }

    private byte[] encodeToBytes(String message, byte[] buffer) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        // If message fits in thread-local buffer, use it for potential reuse
        if (messageBytes.length <= buffer.length) {
            System.arraycopy(messageBytes, 0, buffer, 0, messageBytes.length);
            // Return a properly sized array
            if (messageBytes.length == buffer.length) {
                return buffer;
            } else {
                byte[] result = new byte[messageBytes.length];
                System.arraycopy(buffer, 0, result, 0, messageBytes.length);
                return result;
            }
        }

        return messageBytes; // Falls back to direct byte array
    }

    private void flushBuffer() throws IOException {
        if (writeBuffer == null) {
            throw new IllegalStateException("Write buffer is null");
        }

        final int bytesToFlush = writeBuffer.position();
        if (bytesToFlush == 0) {
            return;  // Nothing to flush
        }

        if (fileChannel == null) {
            throw new IOException("File channel is null");
        }
        if (!fileChannel.isOpen()) {
            throw new IOException("File channel is closed");
        }

        writeBuffer.flip();
        try {
            int bytesWritten = 0;
            while (writeBuffer.hasRemaining()) {
                bytesWritten += fileChannel.write(writeBuffer);
            }
            this.bytesWritten.addAndGet(bytesWritten);
            flushOperations.incrementAndGet();

            if (immediateFlush) {
                fileChannel.force(false);
            }
        } finally {
            writeBuffer.clear();
        }
    }

    private void checkAndRotateFile() throws IOException {
        if (maxFileSize <= 0) return;

        long currentSize = fileChannel.size();
        if (currentSize >= maxFileSize) {
            rotateFile();
        }
    }

    private void rotateFile() throws IOException {
        // This is a simplified rotation - upgrade to ReadWriteLock for async
        channelLock.writeLock().lock();
        try {
            // Flush current buffer
            if (writeBuffer.position() > 0) {
                flushBuffer();
            }

            // Close current channel
            fileChannel.close();

            // Rotate files
            for (int i = maxBackups - 1; i >= 1; i--) {
                Path oldFile = Paths.get(fileName + "." + i);
                Path newFile = Paths.get(fileName + "." + (i + 1));

                if (Files.exists(oldFile)) {
                    Files.move(oldFile, newFile);
                }
            }

            // Move current file to .1
            if (Files.exists(currentFilePath)) {
                Files.move(currentFilePath, Paths.get(fileName + ".1"));
            }

            // Open new file
            openFileChannel();

        } finally {
            channelLock.writeLock().unlock();
        }
    }

    private void openFileChannel() throws IOException {
        this.fileChannel = FileChannel.open(
                currentFilePath,
                StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        this.writeBuffer = ByteBuffer.allocateDirect(bufferSize);
    }

    private void createDirectoriesIfNeeded() throws IOException {
        Path parentDir = currentFilePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public String getName() {
        return name;
    }

    // Performance monitoring methods
    public long getBytesWritten() {
        return bytesWritten.get();
    }

    public long getWriteOperations() {
        return writeOperations.get();
    }

    public long getFlushOperations() {
        return flushOperations.get();
    }

    public double getAverageWriteSize() {
        long ops = writeOperations.get();
        return ops == 0 ? 0 : (double) bytesWritten.get() / ops;
    }

    // Force flush for manual control
    public void flush() throws IOException {
        if (!started) return;

        channelLock.readLock().lock();
        try {
            if (fileChannel != null && fileChannel.isOpen()) {
                flushBuffer();
                fileChannel.force(false);
            }
        } finally {
            channelLock.readLock().unlock();
        }
    }

    // Builder pattern for future extensibility
    public static class Builder {
        private String name;
        private String fileName;
        private Layout<String> layout = new PatternLayout();
        private boolean append = true;
        private boolean immediateFlush = true;
        private long maxFileSize = 100 * 1024 * 1024L; // 100MB
        private int maxBackups = 5;
        private int bufferSize = DEFAULT_BUFFER_SIZE;

        public Builder(String name, String fileName) {
            this.name = name;
            this.fileName = fileName;
        }

        public Builder layout(Layout<String> layout) {
            this.layout = layout;
            return this;
        }

        public Builder append(boolean append) {
            this.append = append;
            return this;
        }

        public Builder immediateFlush(boolean immediateFlush) {
            this.immediateFlush = immediateFlush;
            return this;
        }

        public Builder maxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        public Builder maxBackups(int maxBackups) {
            this.maxBackups = maxBackups;
            return this;
        }

        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public FileAppender build() {
            return new FileAppender(name, fileName, layout, append, immediateFlush,
                    maxFileSize, maxBackups, bufferSize);
        }
    }
}
