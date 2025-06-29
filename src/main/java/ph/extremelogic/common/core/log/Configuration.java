package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.appender.Appender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Configuration {
    private final List<Appender> appenders;
    private final Level rootLevel;
    private volatile boolean started = false;

    public Configuration(Level rootLevel) {
        this.rootLevel = rootLevel;
        this.appenders = new CopyOnWriteArrayList<>(); // Thread-safe for concurrent access
    }

    public void addAppender(Appender appender) {
        appenders.add(appender);
        if (started) {
            appender.start();
        }
    }

    public List<Appender> getAppenders() {
        return appenders;
    }

    public Level getRootLevel() {
        return rootLevel;
    }

    public void start() {
        if (!started) {
            started = true;
            for (Appender appender : appenders) {
                appender.start();
            }
        }
    }

    public void stop() {
        if (started) {
            started = false;
            for (Appender appender : appenders) {
                appender.stop();
            }
        }
    }
}