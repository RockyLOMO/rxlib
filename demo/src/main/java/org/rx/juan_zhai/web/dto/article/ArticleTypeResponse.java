package org.rx.juan_zhai.web.dto.article;

import lombok.Data;

import java.util.UUID;

@Data
public class ArticleTypeResponse {
    private UUID articleTypeId;
    private UUID parentId;
    private String name;
}
