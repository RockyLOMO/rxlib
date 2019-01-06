package org.rx.juan_zhai.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.DataObject;

import java.util.UUID;

/**
 * 新闻类别
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleType extends DataObject {
    private UUID parentId;
    private String name;
    private int deep;
}
