package org.rx.bean;

import lombok.*;
import org.rx.core.Extends;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class DataColumn<T> implements Extends {
    private static final long serialVersionUID = 6716648827629131928L;
    final DataTable table;
    String columnName;
    int ordinal;
    Class<T> dataType;

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
}
