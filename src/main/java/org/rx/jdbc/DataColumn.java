package org.rx.jdbc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@RequiredArgsConstructor
public class DataColumn<T> implements Serializable {
    @Getter
    private final DataTable table;
    @Getter
    int ordinal;
    @Getter
    @Setter
    String columnName;
    @Getter
    Class<T> dataType;

    public void setOrdinal(int ordinal) {
        table.setOrdinal(this, ordinal);
    }

    public <TR> DataColumn<TR> setDataType(Class<TR> dataType) {
        return table.setDataType(this, dataType);
    }
}
