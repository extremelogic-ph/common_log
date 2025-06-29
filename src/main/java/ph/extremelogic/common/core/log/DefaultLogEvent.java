package ph.extremelogic.common.core.log;

import ph.extremelogic.common.core.log.api.Level;
import ph.extremelogic.common.core.log.api.Message;
import ph.extremelogic.common.core.log.api.ThreadContext;
import ph.extremelogic.common.core.log.context.ReadOnlyStringMap;
import ph.extremelogic.common.core.log.context.ThreadInfo;

import java.util.Collections;
import java.util.List;

public final class DefaultLogEvent implements LogEvent {
    private long timeMillis;
    private Level level;
    private int levelInt; // Cache level int for faster access
    private String loggerName;
    private Message message;
    private Throwable thrown;
    private ThreadInfo threadInfo;
    private ReadOnlyStringMap contextData;
    private List<String> contextStack;

    // Pre-allocated Level instances to avoid enum lookups
    private static final Level[] LEVEL_CACHE = {
            Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR
    };

    public void reset(String loggerName, Level level, Message message, Throwable thrown) {
        this.timeMillis = System.currentTimeMillis();
        this.level = level;
        this.levelInt = level.intLevel;
        this.loggerName = loggerName;
        this.message = message;
        this.thrown = thrown;
        this.threadInfo = ThreadInfo.current();
        this.contextData = ThreadContext.getReadOnlyContextData();
        this.contextStack = ThreadContext.getImmutableStack();
    }

    // Ultra-fast reset method that avoids ThreadContext calls for maximum performance
    public void resetFastWithLevel(String loggerName, int levelInt, Message message, Throwable thrown) {
        this.timeMillis = System.currentTimeMillis();
        this.levelInt = levelInt;
        this.level = LEVEL_CACHE[levelInt]; // Direct array access instead of enum lookup
        this.loggerName = loggerName;
        this.message = message;
        this.thrown = thrown;
        this.threadInfo = ThreadInfo.current();
        this.contextData = null; // Skip context for maximum speed
        this.contextStack = Collections.emptyList();
    }

    public void resetFast(String loggerName, Level level, Message message) {
        resetFastWithLevel(loggerName, level.intLevel, message, null);
    }

    @Override public long getTimeMillis() { return timeMillis; }
    @Override public Level getLevel() { return level; }
    @Override public String getLoggerName() { return loggerName; }
    @Override public Message getMessage() { return message; }
    @Override public Throwable getThrown() { return thrown; }
    @Override public ThreadInfo getThreadInfo() { return threadInfo; }
    @Override public ReadOnlyStringMap getContextData() { return contextData; }
    @Override public List<String> getContextStack() { return contextStack; }

    public void setThreadInfo(ThreadInfo threadInfo) {
        this.threadInfo = threadInfo;
    }

    // Add getter for cached int level
    public int getLevelInt() { return levelInt; }
}