package org.rx.fl.model;

import org.rx.NQuery;

public enum AdvNotFoundReason {
    None,
    NoLink,
    NoGoods,
    NoAdv;

    public static AdvNotFoundReason safeValueOf(String name) {
        return NQuery.of(AdvNotFoundReason.values()).skip(1).where(p -> p.name().equals(name)).firstOrDefault();
    }
}
