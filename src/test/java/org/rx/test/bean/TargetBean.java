package org.rx.test.bean;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TargetBean {
    private String     name;
    private String     age;
    private int        luckyNum;
    private BigDecimal money;
    private String     info;
    private Long       kids;
}
