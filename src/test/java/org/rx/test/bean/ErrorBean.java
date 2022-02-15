package org.rx.test.bean;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.rx.exception.InvalidException;

@NoArgsConstructor
@Getter
@Setter
public class ErrorBean implements NestedDefMethod {
    private static String staticCall(int code, String msg) {
        return "S-" + code + msg;
    }

    private int code;
    private String error;

    public int getCode() {
        throw new InvalidException("auto skip");
    }

    private ErrorBean(int code, String msg) {
        error = code + msg;
    }

    private String instanceCall(int code, String msg) {
        return "I-" + code + msg;
    }
}

interface DefMethod {
    default String defCall(int code, String msg) {
        return "D-" + code + msg;
    }
}

interface NestedDefMethod extends DefMethod {
    default String nestedDefCall(int code, String msg) {
        return "N-" + code + msg;
    }
}
