package org.rx.core;

import lombok.Getter;

import java.util.EventObject;
import java.util.Map;

@Getter
public class ObjectChangedEvent extends EventObject {
    private static final long serialVersionUID = -2993269004798534124L;
    final Map<String, ObjectChangeTracker.ChangedValue> changedValues;

    public ObjectChangedEvent(Object source, Map<String, ObjectChangeTracker.ChangedValue> changedValues) {
        super(source);
        this.changedValues = changedValues;
    }
}
