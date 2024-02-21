package org.rx.test;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.bean.ULID;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GirlBean {
    public static GirlBean YouFan = new GirlBean(0, ULID.randomULID(), "湵范", "GIRL", 3, 0, null,
            "hello world", 0L, 64, null, null, null);

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

    private Object obj;
    private int[] flags;
    @Valid
    private GirlBean sister;

    //    @Validated
    @NotNull
    public String renew(@Valid @NotNull List<GirlBean> girls) {
        return null;
    }
}
