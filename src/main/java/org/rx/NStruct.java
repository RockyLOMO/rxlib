package org.rx;

import java.io.Serializable;
import java.lang.reflect.Field;

public abstract class NStruct implements Serializable {
    static final long               serialVersionUID = 42L;
    private transient Lazy<Field[]> fields           = new Lazy<>(() -> this.getClass().getDeclaredFields());

    @Override
    public int hashCode() {
        StringBuilder hex = new StringBuilder();
        for (Field field : fields.getValue()) {
            field.setAccessible(true);
            try {
                Object val = field.get(this);
                if (val != null) {
                    hex.append(val.hashCode());
                }
            } catch (IllegalAccessException ex) {
                throw new SystemException(ex);
            }
        }
        return hex.toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NStruct)) {
            return false;
        }
        NStruct struct = (NStruct) obj;
        return this.hashCode() == struct.hashCode();
    }
}
