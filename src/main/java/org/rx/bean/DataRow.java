package org.rx.bean;

import lombok.Getter;
import lombok.NonNull;
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

    public void setArray(@NonNull Object... array) {
        require(array, array.length == table.columns.size());

        items.clear();
        for (int i = 0; i < array.length; i++) {
            set(i, array[i]);
        }
    }

    public <T> T get(int ordinal) {
        return get(table.getColumn(ordinal));
    }

    public void set(int ordinal, Object item) {
        set(table.getColumn(ordinal), item);
    }

    public <T> T get(String columnName) {
        return get(table.getColumn(columnName));
    }

    public void set(String columnName, Object item) {
        set(table.getColumn(columnName), item);
    }

    public <T> T get(@NonNull DataColumn<T> column) {
        require(column, column.getTable() == table);

        if (items.size() <= column.ordinal) {
            return null;
        }
        return (T) items.get(column.ordinal);
    }

    public <T> void set(@NonNull DataColumn<T> column, T item) {
        require(column, column.getTable() == table);

        if (column.dataType != null && !Reflects.isInstance(item, column.dataType)) {
            throw new InvalidException("Item type error");
        }
        items.add(column.ordinal, item);
    }
}
