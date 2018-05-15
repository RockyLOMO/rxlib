package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.lr.repository.model.common.DataObject;

import java.util.Date;
import java.util.UUID;

/**
 * 新闻
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Article extends DataObject {
    private UUID typeId;
    private String title;
    private String color;
    private String content;
    private Date createTime;
}
