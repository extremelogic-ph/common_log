package ph.extremelogic.common.core.log.appender;

import java.io.IOException;

public interface CloseableAppender {
    void close() throws IOException;
}