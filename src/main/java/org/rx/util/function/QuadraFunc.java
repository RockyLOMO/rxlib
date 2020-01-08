package org.rx.util.function;

@FunctionalInterface
public interface QuadraFunc<T1, T2, T3, TR> {
    TR invoke(T1 param1, T2 param2, T3 param3) throws Throwable;
}
