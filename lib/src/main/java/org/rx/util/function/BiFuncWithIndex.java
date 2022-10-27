package org.rx.util.function;

@FunctionalInterface
public interface BiFuncWithIndex<TP, TR> {
    TR invoke(TP param, int index) throws Throwable;
}
