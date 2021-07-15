package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.bean.DateTime;
import org.rx.bean.Decimal;

import javax.validation.constraints.NotNull;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class PersonBean implements IPerson {
    public static final PersonBean girl = new PersonBean(1, 2, "乐之", PersonGender.Girl,
            4, DateTime.valueOf("2020-02-04 00:00:00"), 100L, Decimal.valueOf(1d));
    public static final PersonBean boy = new PersonBean(3, 4, "湵范", PersonGender.Boy,
            2, DateTime.valueOf("2022-02-04 00:00:00"), 200L, Decimal.valueOf(2d));

    public int index;
    public int index2;
    public final UUID id = UUID.randomUUID();
    @NotNull
    public String name;
    @NotNull
    public PersonGender gender;
    public int age;
    public Date birth;
    public Long moneyCent;
    public Decimal money;

    @Override
    public boolean enableCompress() {
        return gender == PersonGender.Girl;
    }
}
