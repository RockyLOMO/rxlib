package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.beans.DateTime;

import java.util.Date;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class PersonInfo implements IPerson {
    public static final PersonInfo def = new PersonInfo(1, 2, 3, "rx", 6, DateTime.now());

    public int index;
    public int index2;
    public int index3;
    public String name;
    public int age;
    public Date birth;
}
