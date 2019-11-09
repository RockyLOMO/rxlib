package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
public class ErrorBean {
    private static void theStatic(int hint, String nullable) {
        System.out.println("hint3-" + nullable);
    }

    @Getter
    @Setter
    private String error;

    private ErrorBean(int hint, String nullable) {
        error = "hint-" + nullable;
    }

    private void theMethod(int hint, String nullable) {
        this.error = "hint2-" + nullable;
    }
}
