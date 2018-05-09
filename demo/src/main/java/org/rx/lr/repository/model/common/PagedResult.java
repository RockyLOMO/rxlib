package org.rx.lr.repository.model.common;

import lombok.Data;

import java.util.List;

@Data
public class PagedResult<T> {
    private int totalCount;
    private List<T> resultSet;
}
