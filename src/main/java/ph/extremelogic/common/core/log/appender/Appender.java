package ph.extremelogic.common.core.log.appender;

import ph.extremelogic.common.core.log.LogEvent;

public interface Appender {
    void append(LogEvent event);
    void start();
    void stop();
    boolean isStarted();
    String getName();
}

