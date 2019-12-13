package org.rx.test.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.annotation.Description;
import org.rx.beans.NEnum;

@RequiredArgsConstructor
public enum PersonGender implements NEnum<PersonGender> {
    @Description("男孩")
    Boy(1),
    @Description("女孩")
    Girl(2);

    @Getter
    private final int value;
}
