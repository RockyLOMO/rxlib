package org.rx.lr.web.dto.user;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.PagingParam;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryUsersRequest extends PagingParam {
    private UUID userId;
    private String userName;
}
