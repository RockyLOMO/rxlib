package org.rx.bean;

import com.alibaba.fastjson2.JSONObject;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.h2.expression.Alias;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.aggregate.Aggregate;
import org.h2.expression.aggregate.AggregateType;
import org.h2.jdbc.JdbcConnection;
import org.h2.jdbc.JdbcResultSet;
import org.h2.result.LocalResult;
import org.h2.value.ValueToObjectConverter;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.third.guava.CaseFormat;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.tryAs;
import static org.rx.core.Sys.fromJson;

//SimpleResultSet
@Slf4j
@SuppressWarnings(Constants.NON_RAW_TYPES)
@NoArgsConstructor
public class DataTable implements Extends {
    private static final long serialVersionUID = -7379386582995440975L;
    public static final String HS_COLUMN_TYPE = "HS_COLUMN_TYPE";
    public static final String HS_COUNT_MAP = "HS_COUNT_MAP";

    public static DataTable read(ResultSet resultSet) {
        return read(resultSet, false);
    }

    @SneakyThrows
    public static DataTable read(ResultSet resultSet, boolean preferColumnName) {
        DataTable dt = new DataTable();
        try (ResultSet rs = resultSet) {
            ResultSetMetaData metaData = rs.getMetaData();
            dt.setTableName(metaData.getTableName(1));
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                dt.addColumn(preferColumnName ? metaData.getColumnName(i) : metaData.getColumnLabel(i));
            }

            List<Object> buf = new ArrayList<>(columnCount);
            while (rs.next()) {
                buf.clear();
                for (int i = 1; i <= columnCount; i++) {
                    buf.add(rs.getObject(i));
                }
                dt.addRow(buf.toArray());
            }
        }
        return dt;
    }

    @SneakyThrows
    public static DataTable read(JdbcResultSet resultSet) {
        DataTable dt = new DataTable();
        try (JdbcResultSet rs = resultSet) {
            LocalResult result = (LocalResult) rs.getResult();
            //include orderby expr
            Expression[] exprs = Reflects.readField(result, "expressions");
            if (exprs.length > 0) {
                dt.setTableName(exprs[0].getTableName());
            }
            for (Expression expr : exprs) {
                addColumnName(dt, expr);
            }

            JdbcConnection conn = Reflects.readField(rs, "conn");
            int columnCount = exprs.length;
            List<Object> buf = new ArrayList<>(columnCount);
            while (rs.next()) {
                buf.clear();
                for (int i = 1; i <= columnCount; i++) {
                    buf.add(ValueToObjectConverter.valueToDefaultObject(rs.getInternal(i), conn, true));
                }
                dt.addRow(buf.toArray());
            }
        }
        return dt;
    }

    static void addColumnName(DataTable dt, Expression expr) {
        if (tryAs(expr, ExpressionColumn.class, p -> {
            String col = p.getOriginalColumnName();
            if (col == null) {
                col = p.getColumn().getName();
            }
            dt.addColumns(col);
        }) || tryAs(expr, Aggregate.class, p -> {
            if (p.getAggregateType() == AggregateType.COUNT_ALL
                    || p.getAggregateType() == AggregateType.COUNT) {
                String label = p.toString();
                dt.addColumn(label);
                //todo COUNT with no label
                return;
            }
            Expression subExpr = p.getSubexpression(0);
            addColumnName(dt, subExpr);
        }) || tryAs(expr, Alias.class, p -> {
            Expression subExpr = p.getNonAliasExpression();
            Aggregate aggregate = as(subExpr, Aggregate.class);
            if (aggregate != null
                    && (aggregate.getAggregateType() == AggregateType.COUNT_ALL
                    || aggregate.getAggregateType() == AggregateType.COUNT)) {
                String label = p.getAlias(null, 0);
                dt.addColumn(label).attr(HS_COUNT_MAP, Tuple.of(subExpr.toString(), label));
                return;
            }
            addColumnName(dt, subExpr);
        })) ;
    }

    final List<DataColumn> columns = new ArrayList<>();
    final List<DataRow> rows = new ArrayList<>();
    @Getter
    @Setter
    String tableName;
    @Setter
    boolean checkCellsSize = true;
    @Setter
    Iterator<DataRow> fluentRows;
    List<DataColumn> readOnlyCols;

    public List<DataColumn<?>> getColumns() {
        if (readOnlyCols == null) {
            readOnlyCols = Collections.unmodifiableList(columns);
        }
        return (List) readOnlyCols;
    }

    public FluentIterable<DataRow> getRows() {
        return new FluentIterable<DataRow>() {
            Iterator<DataRow> cur = rows.iterator();
            final Iterator<DataRow> next = fluentRows;

            @Override
            public boolean hasNext() {
                if (!cur.hasNext()) {
                    if (cur == next || next == null) {
                        return false;
                    }
                    cur = next;
                    return hasNext();
                }
                return true;
            }

            @Override
            public DataRow next() {
                return cur.next();
            }
        };
    }

    public DataTable(String tableName) {
        this.tableName = tableName;
    }

    public <T> List<T> toList(Type type) {
        return toList(type, false);
    }

    public <T> List<T> toList(@NonNull Type type, boolean toLowerCamelColumn) {
        if (toLowerCamelColumn) {
            for (DataColumn column : columns) {
                column.setColumnName(CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, column.getColumnName()));
            }
        }
        List<T> list = new ArrayList<>();
        int colSize = columns.size();
        Iterator<DataRow> rows = getRows();
        while (rows.hasNext()) {
            List<Object> cells = rows.next().items;
            JSONObject j = new JSONObject(colSize);
            for (int i = 0; i < colSize; i++) {
                j.put(columns.get(i).columnName, cells.get(i));
            }
            list.add(fromJson(j, type));
        }
        return list;
    }

    public DataRow addRow(Object... cells) {
        DataRow row = newRow(cells);
        rows.add(row);
        return row;
    }

    public DataRow addRow(DataRow row) {
        if (row.table != this) {
            row = newRow(row.getArray());
        }
        rows.add(row);
        return row;
    }

    public DataRow removeRow(DataRow row) {
        rows.remove(row);
        return row;
    }

    public DataRow newRow(Object... cells) {
        DataRow row = new DataRow(this);
        if (!Arrays.isEmpty(cells)) {
            row.setArray(cells);
        }
        return row;
    }

    public List<DataColumn<?>> addColumns(String... columnNames) {
        List<DataColumn<Object>> columns = Linq.from(columnNames).select(this::addColumn).toList();
        return (List) columns;
    }

    public <T> DataColumn<T> addColumn(String columnName) {
        DataColumn<T> column = new DataColumn<>(this);
        column.ordinal = columns.size();
        column.columnName = columnName;
        columns.add(column);
        return column;
    }

    public <T> DataColumn<T> removeColumn(String columnName) {
        int index = getColumn(columnName).ordinal;
        DataColumn column = columns.remove(index);
        for (DataRow row : rows) {
            row.items.remove(index);
        }
        return column;
    }

    public <T> DataColumn<T> getColumn(int ordinal) {
        return columns.get(ordinal);
    }

    public <T> DataColumn<T> getColumn(String columnName) {
        return Linq.from(columns).first(p -> Strings.hashEquals(p.columnName, columnName));
    }

    <T> void setOrdinal(DataColumn<T> column, int ordinal) {
        if (fluentRows != null) {
            throw new InvalidException("Not supported");
        }
        if (column.ordinal == ordinal) {
            return;
        }

        columns.remove(ordinal);
        columns.add(ordinal, column);
        for (DataRow row : rows) {
            row.items.add(ordinal, row.items.remove(ordinal));
        }
        column.ordinal = ordinal;
    }

    <TR> DataColumn<TR> setDataType(DataColumn column, Class<TR> dataType) {
        if (fluentRows != null) {
            throw new InvalidException("Not supported");
        }
        if (Reflects.isAssignable(column.dataType, dataType)) {
            return (DataColumn<TR>) column;
        }

        for (DataRow row : rows) {
            row.items.set(column.ordinal, Reflects.changeType(row.items.get(column.ordinal), dataType));
        }
        column.dataType = dataType;
        return (DataColumn<TR>) column;
    }

    @Override
    public String toString() {
        StringBuilder txt = new StringBuilder();
        for (DataColumn<?> column : getColumns()) {
            txt.append(column.getColumnName()).append("\t");
        }
        txt.appendLine();
        Iterator<DataRow> rows = getRows();
        while (rows.hasNext()) {
            for (Object item : rows.next().items) {
                txt.append(item).append("\t");
            }
            txt.appendLine();
        }
        return txt.toString();
    }
}
