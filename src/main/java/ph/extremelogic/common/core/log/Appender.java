package ph.extremelogic.common.core.log;

import java.io.IOException;

/**
 * Appender interface for different logging destinations
 */
public interface Appender {
    void append(String message);

    void append(String message, Throwable throwable);

    void initialize() throws IOException;

    String getName();
}
