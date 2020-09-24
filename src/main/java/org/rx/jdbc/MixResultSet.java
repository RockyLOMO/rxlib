package org.rx.jdbc;

import lombok.AccessLevel;
import lombok.Getter;
import org.rx.core.NQuery;
import org.rx.core.exception.InvalidException;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;

import static org.rx.core.Contract.eq;
import static org.rx.core.Contract.require;

public class MixResultSet extends SuperResultSet {
    private String schemaName;
    @Getter(AccessLevel.PROTECTED)
    private DataTable dataTable;
    private Iterator<DataRow> rows;
    private int rowNo;
    private DataRow row;

    public MixResultSet(Statement statement, String schemaName, DataTable dataTable) {
        super(statement);
        require(dataTable);

        this.schemaName = schemaName;
        this.dataTable = dataTable;
    }

    @Override
    protected Object innerGetObject(int columnIndex) {
        if (row == null) {
            throw new InvalidException("No data");
        }
        return row.get(columnIndex - 1);
    }

    @Override
    public boolean next() {
        if (rows == null) {
            rows = dataTable.getRows();
        }
        boolean has = rows.hasNext();
        if (has) {
            rowNo++;
            row = rows.next();
        } else {
            row = null;
        }
        return has;
    }

    @Override
    public int getRow() {
        return rowNo;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new SimpleResultSetMetaData(NQuery.of(dataTable.getColumns()).select(DataColumn::getColumnName).toList(), i -> rows == null || !rows.hasNext() ? null : rows.next().get(i - 1), i -> dataTable.getTableName(), i -> schemaName);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < dataTable.getColumns().size(); i++) {
            if (eq(dataTable.getColumns().get(i).getColumnName(), columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Not contain a column labeled");
    }
}
