package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.bean.DateTime;

import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class PersonBean implements IPerson {
    public static final PersonBean def = new PersonBean(1, 2, "王湵范", PersonGender.Girl, 6, DateTime.now(), 100L);

    public int index;
    public int index2;
    public final UUID id = UUID.randomUUID();
    public String name;
    public PersonGender gender;
    public int age;
    public Date birth;
    public Long money;
}
