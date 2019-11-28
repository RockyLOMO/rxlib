package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
public class PersonInfo implements IPerson {
    public static final PersonInfo def = new PersonInfo(1, 2, 3, "rx", 6);

    public int index;
    public int index2;
    public int index3;
    public String name;
    public int age;
}
