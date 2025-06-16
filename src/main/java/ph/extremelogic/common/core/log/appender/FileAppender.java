package ph.extremelogic.common.core.log.appender;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Optimized FileAppender - minimal changes to your original code
 */
public class FileAppender implements Appender, AutoCloseable {
    private final String logFileName;
    private BufferedWriter writer; // Changed from Writer to BufferedWriter
    private final Object lock = new Object();
    private volatile boolean initialized = false;
    private volatile boolean closed = false; // Added closed flag

    public FileAppender(String logFileName) {
        this.logFileName = logFileName;
    }

    @Override
    public void append(String message) {
        if (closed) return; // Early return if closed

        synchronized (lock) {
            if (closed) return; // Double-check after acquiring lock

            try {
                initializeIfNeeded();
                writer.write(message);
                // OPTIMIZATION 1: Remove immediate flush for better performance
                // writer.flush(); // Comment this out for batched writes
            } catch (IOException e) {
                handleIOError(e);
            }
        }
    }

    @Override
    public void append(String message, Throwable throwable) {
        if (closed) return; // Early return if closed

        synchronized (lock) {
            if (closed) return; // Double-check after acquiring lock

            try {
                initializeIfNeeded();
                writer.write(message);
                if (throwable != null) {
                    // OPTIMIZATION 2: Use StringWriter instead of wrapping file writer
                    StringWriter sw = new StringWriter();
                    try (PrintWriter pw = new PrintWriter(sw)) {
                        throwable.printStackTrace(pw);
                    }
                    writer.write(sw.toString());
                }
                // OPTIMIZATION 1: Remove immediate flush for better performance
                // writer.flush(); // Comment this out for batched writes
            } catch (IOException e) {
                handleIOError(e);
            }
        }
    }

    private void initializeIfNeeded() throws IOException {
        if (!initialized) {
            writer = new BufferedWriter(new FileWriter(logFileName, true));
            initialized = true;
        }
    }

    private void handleIOError(IOException e) {
        System.err.println("Failed to write to log file: " + e.getMessage());
        // OPTIMIZATION 3: Reset writer on error to allow recovery
        try {
            if (writer != null) {
                writer.close();
                writer = null;
                initialized = false;
            }
        } catch (IOException closeException) {
            // Ignore close exceptions during error handling
        }
    }

    @Override
    public void initialize() {
        // No-op - initialization happens on first append
    }

    @Override
    public String getName() {
        return "FILE:" + logFileName;
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            closed = true; // Set closed flag
            if (writer != null) {
                try {
                    writer.flush(); // Ensure data is written before closing
                    writer.close();
                } finally {
                    writer = null; // Null the reference
                    initialized = false;
                }
            }
        }
    }

    // OPTIMIZATION 4: Add manual flush method
    public void flush() throws IOException {
        synchronized (lock) {
            if (writer != null && !closed) {
                writer.flush();
            }
        }
    }

    public String getLogFileName() {
        return logFileName;
    }
}