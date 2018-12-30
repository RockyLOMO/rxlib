package org.rx.common;

import lombok.Data;

@Data
public class RestResult<T> {
    private int code;
    private String msg;
    private T value;
}
