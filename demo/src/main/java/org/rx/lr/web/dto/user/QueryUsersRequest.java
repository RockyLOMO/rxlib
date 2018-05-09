package org.rx.lr.web.dto.user;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.lr.web.dto.common.PagingRequest;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryUsersRequest extends PagingRequest {
    private String userName;
}
