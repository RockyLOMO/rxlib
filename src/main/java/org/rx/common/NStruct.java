package org.rx.common;

import java.io.Serializable;
import java.lang.reflect.Field;

import static org.rx.common.Contract.wrapCause;

public abstract class NStruct implements Serializable {
    static final long         serialVersionUID = 42L;
    private transient Field[] fields;

    private Field[] getFields() {
        if (fields == null) {
            fields = this.getClass().getDeclaredFields();
        }
        return fields;
    }

    @Override
    public int hashCode() {
        StringBuilder hex = new StringBuilder();
        for (Field field : getFields()) {
            //System.out.println("item " + field.getName());
            field.setAccessible(true);
            try {
                Object val = field.get(this);
                if (val != null) {
                    hex.append(val.hashCode());
                }
            } catch (IllegalAccessException ex) {
                throw Contract.wrapCause(ex);
            }
        }
        //System.out.println(String.format("hashCode=%s", hex.toString()));
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
