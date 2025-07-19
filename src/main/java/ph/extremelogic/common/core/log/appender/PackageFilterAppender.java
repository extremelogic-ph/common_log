package ph.extremelogic.common.core.log.appender;

import ph.extremelogic.common.core.log.LogEvent;
import java.util.regex.Pattern;

public final class PackageFilterAppender implements Appender {

    private final String name;
    private final Appender delegate;
    private final Pattern includePattern;
    private final Pattern excludePattern;
    private volatile boolean started;

    public PackageFilterAppender(String name,
                                 Appender delegate,
                                 String include,
                                 String exclude) {
        this.name = name;
        this.delegate = delegate;
        this.includePattern = include == null ? null : toRegex(include);
        this.excludePattern = exclude == null ? null : toRegex(exclude);
    }

    private static Pattern toRegex(String raw) {
        String regex = raw
                .replace(".", "\\.")
                .replace("*", ".*");
        if (raw.startsWith("!")) {
            return Pattern.compile(regex.substring(1));
        }
        return Pattern.compile("^" + regex + "$");
    }

    @Override
    public void append(LogEvent event) {
        if (!started) return;

        String loggerName = event.getLoggerName();
        if (excludePattern != null && excludePattern.matcher(loggerName).matches()) {
            return;
        }
        if (includePattern == null || includePattern.matcher(loggerName).matches()) {
            delegate.append(event);
        }
    }

    @Override public void start() {
        started = true;
        delegate.start();
    }

    @Override public void stop() {
        started = false;
        delegate.stop();
    }

    @Override public boolean isStarted() {
        return started && delegate.isStarted();
    }

    @Override public String getName() {
        return name;
    }
}
