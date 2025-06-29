package ph.extremelogic.common.core.log.context;

import java.util.Map;

public interface ReadOnlyStringMap {
    String getValue(String key);
    boolean containsKey(String key);
    Map<String, String> toMap();
    int size();
    boolean isEmpty();
}