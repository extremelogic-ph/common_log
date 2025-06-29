package ph.extremelogic.common.core.log.api;

import ph.extremelogic.common.core.log.context.ReadOnlyStringMap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadContext {
    private static final ThreadLocal<Map<String, String>> CONTEXT_MAP =
            ThreadLocal.withInitial(() -> new ConcurrentHashMap<>(8));

    private static final ThreadLocal<Deque<String>> CONTEXT_STACK =
            ThreadLocal.withInitial(() -> new ArrayDeque<>(4));

    // Empty implementations for better performance when not used
    private static final ReadOnlyStringMap EMPTY_CONTEXT_DATA = new EmptyStringMap();
    private static final List<String> EMPTY_STACK = Collections.emptyList();

    public static void put(String key, String value) {
        CONTEXT_MAP.get().put(key, value);
    }

    public static String get(String key) {
        return CONTEXT_MAP.get().get(key);
    }

    public static void remove(String key) {
        CONTEXT_MAP.get().remove(key);
    }

    public static void clear() {
        CONTEXT_MAP.get().clear();
    }

    public static void push(String message) {
        CONTEXT_STACK.get().push(message);
    }

    public static String pop() {
        Deque<String> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.pop();
    }

    public static String peek() {
        Deque<String> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public static ReadOnlyStringMap getReadOnlyContextData() {
        Map<String, String> map = CONTEXT_MAP.get();
        return map.isEmpty() ? EMPTY_CONTEXT_DATA : new ThreadContextMap(map);
    }

    public static List<String> getImmutableStack() {
        Deque<String> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? EMPTY_STACK : new ArrayList<>(stack);
    }

    public static void cleanup() {
        CONTEXT_MAP.remove();
        CONTEXT_STACK.remove();
    }

    // Inner class for efficient context data access
    private static final class ThreadContextMap implements ReadOnlyStringMap {
        private final Map<String, String> data;

        ThreadContextMap(Map<String, String> data) {
            this.data = new HashMap<>(data); // Defensive copy
        }

        @Override
        public String getValue(String key) {
            return data.get(key);
        }

        @Override
        public boolean containsKey(String key) {
            return data.containsKey(key);
        }

        @Override
        public Map<String, String> toMap() {
            return new HashMap<>(data);
        }

        @Override
        public int size() {
            return data.size();
        }

        @Override
        public boolean isEmpty() {
            return data.isEmpty();
        }
    }

    private static final class EmptyStringMap implements ReadOnlyStringMap {
        @Override
        public String getValue(String key) { return null; }
        @Override
        public boolean containsKey(String key) { return false; }
        @Override
        public Map<String, String> toMap() { return Collections.emptyMap(); }
        @Override
        public int size() { return 0; }
        @Override
        public boolean isEmpty() { return true; }
    }
}