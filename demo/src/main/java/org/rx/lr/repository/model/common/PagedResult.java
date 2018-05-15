package org.rx.lr.repository.model.common;

import lombok.Data;
import org.rx.NQuery;

import java.util.List;
import java.util.function.Function;

@Data
public class PagedResult<T> {
    private int totalCount;
    private List<T> resultSet;

    public <TR> PagedResult<TR> convert(Function<T, TR> convert) {
        PagedResult r = this;
        r.resultSet = NQuery.of(resultSet).select(convert).toList();
        return r;
    }
}
