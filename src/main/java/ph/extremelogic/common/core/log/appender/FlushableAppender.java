package ph.extremelogic.common.core.log.appender;

import java.io.IOException;

public interface FlushableAppender {
    void flush() throws IOException;
}
