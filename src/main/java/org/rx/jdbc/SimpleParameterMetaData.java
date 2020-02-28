package org.rx.jdbc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.sql.ParameterMetaData;
import java.util.function.Function;

@RequiredArgsConstructor
public class SimpleParameterMetaData extends SuperJdbc implements ParameterMetaData {
    @Getter
    private final int parameterCount;
    private final Function<Integer, Object> getRow;

    @Override
    public int isNullable(int param) {
        return getRow.apply(param) == null ? parameterNullable : parameterNoNulls;
    }

    @Override
    public boolean isSigned(int param) {
        return false;
    }

    @Override
    public int getPrecision(int param) {
        return 0;
    }

    @Override
    public int getScale(int param) {
        return 0;
    }

    @Override
    public int getParameterType(int param) {
        return JdbcUtil.getSqlType(getRow.apply(param));
    }

    @Override
    public String getParameterTypeName(int param) {
        return JdbcUtil.getSqlTypeName(getParameterType(param));
    }

    @Override
    public String getParameterClassName(int param) {
        Object v = getRow.apply(param);
        return (v == null ? Object.class : v.getClass()).getName();
    }

    @Override
    public int getParameterMode(int param) {
        return parameterModeIn;
    }
}
