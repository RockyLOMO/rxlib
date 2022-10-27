package org.rx.test.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.annotation.Description;
import org.rx.bean.NEnum;

@RequiredArgsConstructor
public enum PersonGender implements NEnum<PersonGender> {
    @Description("男孩")
    BOY(1),
    @Description("女孩")
    GIRL(2);

    @Getter
    private final int value;
}
