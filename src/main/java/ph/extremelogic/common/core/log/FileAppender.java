package ph.extremelogic.common.core.log;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * File appender implementation
 */
public class FileAppender implements Appender {
    private final String logFileName;
    private volatile boolean initialized = false;

    public FileAppender(String logFileName) {
        this.logFileName = logFileName;
    }

    @Override
    public void append(String message) {
        if (!initialized) {
            throw new IllegalStateException("FileAppender not initialized");
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
            writer.print(message);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    @Override
    public void append(String message, Throwable throwable) {
        if (!initialized) {
            throw new IllegalStateException("FileAppender not initialized");
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
            writer.print(message);
            if (throwable != null) {
                throwable.printStackTrace(writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    @Override
    public void initialize() throws IOException {
        if (!initialized) {
            // Test if we can write to the file
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
                writer.print(""); // Just test write access
            }
            initialized = true;
        }
    }

    @Override
    public String getName() {
        return "FILE:" + logFileName;
    }

    public String getLogFileName() {
        return logFileName;
    }
}
