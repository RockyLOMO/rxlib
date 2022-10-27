package org.rx.bean;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.*;
import org.rx.core.Constants;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings(Constants.NON_UNCHECKED)
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class DataColumn<T> extends AbstractAttributes {
    private static final long serialVersionUID = 6716648827629131928L;
    @JSONField(serialize = false)
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

    @Override
    protected <TK, TV> Map<TK, TV> initialAttrs() {
        return new HashMap<>();
    }
}
