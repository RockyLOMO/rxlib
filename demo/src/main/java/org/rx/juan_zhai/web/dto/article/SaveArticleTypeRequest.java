package org.rx.juan_zhai.web.dto.article;

import lombok.Data;

import javax.validation.Contract.aints.NotNull;
import java.util.UUID;

@Data
public class SaveArticleTypeRequest {
    private UUID articleTypeId;
    private UUID parentId;
    @NotNull
    private String name;

    private boolean deleting;
}
