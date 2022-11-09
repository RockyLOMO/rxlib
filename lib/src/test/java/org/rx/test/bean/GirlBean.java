package org.rx.test.bean;

import lombok.Data;
import org.rx.bean.ULID;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class GirlBean {
    public int index;
    @NotNull
    private ULID id;
    @NotNull
    private String name;
    private String gender;
    private Integer age;
    private int birth;
    private BigDecimal cash;

    private String info;
    private Long kids;
    private int luckyNum;
}
