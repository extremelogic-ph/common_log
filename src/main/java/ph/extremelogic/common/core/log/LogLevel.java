package ph.extremelogic.common.core.log;

public enum LogLevel {
    TRACE(0, "TRACE"),
    DEBUG(1, "DEBUG"),
    INFO(2, "INFO "),
    WARN(3, "WARN "),
    ERROR(4, "ERROR");

    private final int priority;
    private final String label;

    LogLevel(int priority, String label) {
        this.priority = priority;
        this.label = label;
    }

    public int getPriority() {
        return priority;
    }

    public String getLabel() {
        return label;
    }
}
