package org.rx.bean;

import org.rx.core.App;

import java.io.Serializable;

public interface DeepCloneable extends Serializable {
    default <T> T deepClone() {
        return (T) App.deepClone(this);
    }
}
