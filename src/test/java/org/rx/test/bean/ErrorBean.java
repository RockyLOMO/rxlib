package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.rx.exception.InvalidException;

import java.util.Collections;

@NoArgsConstructor
@AllArgsConstructor
public class ErrorBean implements NestedDefMethod {
    private static void staticCall(int hint, String nullable) {
        System.out.println("staticCall:" + Collections.singletonMap(hint, nullable));
    }

    @Getter
    @Setter
    private String error;

    public int getCode() {
        throw new InvalidException("auto skip");
    }

    private ErrorBean(int hint, String nullable) {
        error = "hint-" + nullable;
    }

    private void instanceCall(int hint, String nullable) {
        System.out.println("instanceCall:" + Collections.singletonMap(hint, nullable));
    }
}

interface DefMethod {
    default void defCall(int hint, String nullable) {
        System.out.println("defCall:" + Collections.singletonMap(hint, nullable));
    }
}

interface NestedDefMethod extends DefMethod {
    default void nestedDefCall(int hint, String nullable) {
        System.out.println("nestedDefCall:" + Collections.singletonMap(hint, nullable));
    }
}
