package ph.extremelogic.common.core.log.appender;

import java.io.IOException;

/**
 * Interface for appenders that support batch operations
 */
public interface BatchAppender {
    void appendBatch(String[] messages, Throwable[] throwables) throws IOException;
}
