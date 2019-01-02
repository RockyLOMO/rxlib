package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.DataObject;

import java.util.UUID;

/**
 * 述求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserComment extends DataObject {
    private UUID userId;
    private String content;
}
