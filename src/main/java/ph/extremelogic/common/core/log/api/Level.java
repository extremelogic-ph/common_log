package ph.extremelogic.common.core.log.api;

public enum Level {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    OFF(Integer.MAX_VALUE);  // OFF has the highest int level to disable all logging

    public final int intLevel;

    Level(int intLevel) {
        this.intLevel = intLevel;
    }

    public int getIntLevel() {
        return intLevel;
    }

    public boolean isMoreSpecificThan(Level other) {
        return this.intLevel >= other.intLevel;
    }

    public boolean isLessSpecificThan(Level other) {
        return this.intLevel <= other.intLevel;
    }

    public static Level getLevel(String name) {
        if (name == null) return null;
        try {
            return Level.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Helper method to check if logging is completely disabled
    public boolean isOff() {
        return this == OFF;
    }

    // Static convenience method
    public static boolean isLoggingEnabled(Level configuredLevel, Level messageLevel) {
        return configuredLevel != OFF && messageLevel.intLevel >= configuredLevel.intLevel;
    }
}