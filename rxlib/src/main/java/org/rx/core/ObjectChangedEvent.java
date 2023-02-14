package org.rx.core;

import lombok.Getter;

import java.util.EventObject;
import java.util.Map;

@Getter
public class ObjectChangedEvent extends EventObject {
    private static final long serialVersionUID = -2993269004798534124L;
    final Map<String, ObjectChangeTracker.ChangedValue> changedMap;

    public ObjectChangedEvent(Object source, Map<String, ObjectChangeTracker.ChangedValue> changedMap) {
        super(source);
        this.changedMap = changedMap;
    }

    public <T> T source() {
        return (T) getSource();
    }

    public <T> T readValue(String path) {
        return readValue(path, false);
    }

    public <T> T readValue(String path, boolean throwOnEmptyChild) {
        return Sys.readJsonValue(changedMap, path, p -> {
            if (p instanceof ObjectChangeTracker.ChangedValue) {
                return ((ObjectChangeTracker.ChangedValue) p).newValue();
            }
            return p;
        }, throwOnEmptyChild);
    }
}
