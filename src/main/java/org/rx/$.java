package org.rx;

public final class $<T> {
    private static final ThreadLocal<$> ThreadStatic = ThreadLocal.withInitial($::new);

    /**
     * Alt+Enter
     * 
     * @param <T>
     * @return
     */
    public static <T> $<T> $() {
        return ThreadStatic.get();
    }

    public T $;

    private $() {
    }
}
