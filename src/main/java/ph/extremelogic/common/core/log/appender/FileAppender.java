package ph.extremelogic.common.core.log.appender;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Optimized FileAppender with single file handle management
 */
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

public class FileAppender implements Appender, AutoCloseable {
    private final String logFileName;
    private Writer writer;
    private final Object lock = new Object();
    private volatile boolean initialized = false;

    public FileAppender(String logFileName) {
        this.logFileName = logFileName;
    }

    @Override
    public void append(String message) {
        synchronized (lock) {
            try {
                initializeIfNeeded();
                writer.write(message);
                writer.flush();
            } catch (IOException e) {
                handleIOError(e);
            }
        }
    }

    @Override
    public void append(String message, Throwable throwable) {
        synchronized (lock) {
            try {
                initializeIfNeeded();
                writer.write(message);
                if (throwable != null) {
                    try (PrintWriter pw = new PrintWriter(writer)) {
                        throwable.printStackTrace(pw);
                    }
                }
                writer.flush();
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
            if (writer != null) {
                writer.close();
            }
            initialized = false;
        }
    }

    public String getLogFileName() {
        return logFileName;
    }
}