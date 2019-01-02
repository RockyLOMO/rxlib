package org.rx.repository;

import lombok.Data;
import org.rx.NQuery;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class PagingParam {
    @Min(1)
    private int pageIndex;

    @Min(1)
    private int pageSize;

    public <T> PagedResult<T> page(@NotNull NQuery<T> nQuery) {
        PagedResult<T> result = new PagedResult<>();
        result.setTotalCount(nQuery.count());
        result.setResultSet(nQuery.skip((pageIndex - 1) * pageSize).take(pageSize).toList());
        return result;
    }
}
