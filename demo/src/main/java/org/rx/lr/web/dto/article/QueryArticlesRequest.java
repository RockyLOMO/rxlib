package org.rx.lr.web.dto.article;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.lr.repository.model.common.PagingParam;

import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryArticlesRequest extends PagingParam {
    private UUID articleId;
    private String title;

    private List<UUID> typeIds;
}
