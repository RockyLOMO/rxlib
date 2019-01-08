package org.rx.juan_zhai.web.dto.article;

import lombok.Data;

import javax.validation.Contract.aints.NotNull;
import java.util.UUID;

@Data
public class SaveArticleRequest {
    private UUID articleId;
    @NotNull
    private UUID typeId;
    @NotNull
    private String title;
    private String color;
    private String content;
}
