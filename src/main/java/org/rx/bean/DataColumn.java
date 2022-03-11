package org.rx.bean;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.*;
import org.rx.core.Constants;
import org.rx.core.Extends;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings(Constants.NON_UNCHECKED)
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class DataColumn<T> implements Extends {
    private static final long serialVersionUID = 6716648827629131928L;
    @JSONField(serialize = false)
    final DataTable table;
    String columnName;
    int ordinal;
    Class<T> dataType;
    Map attrs;

    public DataColumn<T> setColumnName(String columnName) {
        this.columnName = columnName;
        return this;
    }

    public DataColumn<T> setOrdinal(int ordinal) {
        table.setOrdinal(this, ordinal);
        return this;
    }

    public <TR> DataColumn<TR> setDataType(@NonNull Class<TR> dataType) {
        return table.setDataType(this, dataType);
    }

    @Override
    public <TK, TV> TV attr(TK key) {
        if (attrs == null) {
            return null;
        }
        return (TV) attrs.get(key);
    }

    @Override
    public <TK, TV> void attr(TK key, TV value) {
        if (attrs == null) {
            attrs = new HashMap<>();
        }
        attrs.put(key, value);
    }
}
