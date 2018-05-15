package org.rx.lr.web.dto.common;

public class RestResult<T> {
    private int code;
    private String msg;
    private T value;
}
