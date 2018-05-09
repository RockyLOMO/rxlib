package org.rx.lr.web.dto.common;

import lombok.Data;

import java.util.List;

@Data
public class PagedResponse<T> {
    private int totalCount;
    private List<T> resultSet;
}
