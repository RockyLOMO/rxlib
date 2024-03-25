package org.rx.bean;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Getter;
import lombok.NonNull;
import org.rx.core.Arrays;
import org.rx.core.Constants;
import org.rx.core.Extends;
import org.rx.core.Reflects;
import org.rx.exception.InvalidException;

import java.util.ArrayList;
import java.util.List;

import static org.rx.core.Extends.require;

@SuppressWarnings(Constants.NON_UNCHECKED)
public class DataRow implements Extends {
    private static final long serialVersionUID = 252345291901055072L;
    @JSONField(serialize = false)
    @Getter
    final DataTable table;
    final List<Object> items;

    DataRow(DataTable table) {
        this.table = table;
        items = new ArrayList<>(table.columns.size());
    }

    public Object[] getArray() {
        return items.toArray();
    }

    public void setArray(Object... array) {
        if (array == null) {
            array = Arrays.EMPTY_OBJECT_ARRAY;
        }
        int colSize = table.columns.size();
        if (array.length != colSize) {
            if (table.checkCellsSize) {
                throw new InvalidException("Array length does not match {}", colSize);
            }
            Object[] tmp = new Object[colSize];
            System.arraycopy(array, 0, tmp, 0, Math.min(array.length, colSize));
            array = tmp;
        }

        items.clear();
        for (int i = 0; i < array.length; i++) {
            set(i, array[i]);
        }
    }

    public <T> T get(int ordinal) {
        return get(table.getColumn(ordinal));
    }

    public void set(int ordinal, Object cell) {
        set(table.getColumn(ordinal), cell);
    }

    public <T> T get(String columnName) {
        return get(table.getColumn(columnName));
    }

    public void set(String columnName, Object cell) {
        set(table.getColumn(columnName), cell);
    }

    public <T> T get(@NonNull DataColumn<T> column) {
        require(column, column.getTable() == table);

        if (items.size() <= column.ordinal) {
            return null;
        }
        return (T) items.get(column.ordinal);
    }

    public <T> void set(@NonNull DataColumn<T> column, T cell) {
        require(column, column.getTable() == table);

        if (column.dataType != null && !Reflects.isInstance(cell, column.dataType)) {
            throw new InvalidException("Item type error");
        }
        if (column.ordinal < items.size()) {
            items.set(column.ordinal, cell);
        } else {
            items.add(column.ordinal, cell);
        }
    }
}
