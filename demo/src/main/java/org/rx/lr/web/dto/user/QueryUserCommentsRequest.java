package org.rx.lr.web.dto.user;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.PagingParam;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryUserCommentsRequest extends PagingParam {
    private String mobile;
}
