package org.rx.test.bean;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TargetBean {
    @NotNull
    private UUID id;
    @NotNull
    private String name;
    private String gender;
    private Integer age;
    private int birth;
    private BigDecimal money;

    private String info;
    private Long kids;
    private int luckyNum;
}
