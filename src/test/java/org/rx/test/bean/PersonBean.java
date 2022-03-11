package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.annotation.DbColumn;
import org.rx.annotation.Description;
import org.rx.bean.DateTime;
import org.rx.bean.Decimal;
import org.rx.core.Arrays;
import org.rx.core.Extends;

import javax.validation.constraints.NotNull;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Description("person")
public class PersonBean implements IPerson, Extends {
    public static final int[] Flags = new int[]{2, 4};
    public static final Object[] Array = new Object[]{0, "a"};
    public static final PersonBean LeZhi = new PersonBean(1, 2, "乐之", PersonGender.GIRL,
            2, DateTime.valueOf("2020-02-04 00:00:00"), 100L, Decimal.valueOf(1d), Flags, Array);
    public static final PersonBean YouFan = new PersonBean(3, 4, "湵范", PersonGender.BOY,
            3, DateTime.valueOf("2019-02-04 00:00:00"), 200L, Decimal.valueOf(2d), Arrays.EMPTY_INT_ARRAY, Arrays.EMPTY_OBJECT_ARRAY);

    @DbColumn(primaryKey = true)
    public final UUID id = UUID.randomUUID();
    @DbColumn(index = DbColumn.IndexKind.NONE)
    public int index;
    @DbColumn(index = DbColumn.IndexKind.NONE)
    public int index2;
    @NotNull
    @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
    public String name;
    @NotNull
    public PersonGender gender;
    public int age;
    public Date birth;
    public Long moneyCent;
    public Decimal money;
    public int[] flags;
    public Object[] array;

    @Override
    public boolean enableCompress() {
        return gender == PersonGender.GIRL;
    }
}
