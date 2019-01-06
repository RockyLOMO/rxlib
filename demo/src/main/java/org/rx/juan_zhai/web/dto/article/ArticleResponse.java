package org.rx.juan_zhai.web.dto.article;

import lombok.Data;

import java.util.UUID;

@Data
public class ArticleResponse {
    private UUID articleId;
    private UUID typeId;
    private String title;
    private String color;
    private String content;
}
