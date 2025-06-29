package ph.extremelogic.common.core.log.layout;

import ph.extremelogic.common.core.log.LogEvent;

public interface Layout<T> {
    T toSerializable(LogEvent event);
    String getContentType();
    byte[] getHeader();
    byte[] getFooter();
}
