package ph.extremelogic.common.core.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoggerContext {
    private final Map<String, Logger> loggerMap = new ConcurrentHashMap<>();
    private final Configuration configuration;

    public LoggerContext(Configuration configuration) {
        this.configuration = configuration;
    }

    public Logger getLogger(String name) {
        String normalizedName = (name == null) ? "null" : name;
        return loggerMap.computeIfAbsent(normalizedName, n -> new Logger(n, configuration));
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}