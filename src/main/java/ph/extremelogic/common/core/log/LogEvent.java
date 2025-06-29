package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.api.Message;
import ph.extremelogic.common.core.log.context.ReadOnlyStringMap;
import ph.extremelogic.common.core.log.context.ThreadInfo;

import java.util.List;

public interface LogEvent {
    long getTimeMillis();
    Level getLevel();
    String getLoggerName();
    Message getMessage();
    Throwable getThrown();
    ThreadInfo getThreadInfo();
    ReadOnlyStringMap getContextData();
    List<String> getContextStack();
}
