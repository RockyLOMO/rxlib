package org.rx.test.bean;

import lombok.Data;

import java.io.Serializable;

@Data
public class SourceBean implements Serializable {
    private String  name;
    private int     age;
    private boolean gender;
    private Long    money;
    private Long    kids;
}
