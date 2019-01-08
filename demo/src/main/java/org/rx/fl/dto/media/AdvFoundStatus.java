package org.rx.fl.dto.media;

import org.rx.NQuery;

public enum AdvFoundStatus {
    Ok,
    NoLink,
    NoGoods,
    NoAdv;

    public static AdvFoundStatus safeValueOf(String name) {
        return NQuery.of(AdvFoundStatus.values()).skip(1).where(p -> p.name().equals(name)).firstOrDefault();
    }
}
