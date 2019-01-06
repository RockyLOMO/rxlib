package org.rx.juan_zhai.web.dto.article;

import lombok.Data;

import java.util.UUID;

@Data
public class QueryArticleTypesRequest {
    private UUID articleTypeId;
    private String name;
}
