package org.rx.lr.web.dto.common;

import lombok.Data;
import org.rx.NQuery;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class PagingRequest {
    @Min(1)
    private int pageIndex;

    @Min(1)
    private int pageSize;

    public <T> PagedResponse<T> page(@NotNull NQuery<T> nQuery) {
        PagedResponse<T> result = new PagedResponse<>();
        result.setTotalCount(nQuery.count());
        result.setResultSet(nQuery.skip((pageIndex - 1) * pageSize).take(pageSize).toList());
        return result;
    }
}
