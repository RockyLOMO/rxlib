package org.rx.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.annotation.Metadata;

@RequiredArgsConstructor
public enum PersonGender implements NEnum<PersonGender> {
    @Metadata("男孩")
    BOY(1),
    @Metadata("女孩")
    GIRL(2);

    @Getter
    private final int value;
}
