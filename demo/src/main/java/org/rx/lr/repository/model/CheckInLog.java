package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.lr.repository.model.common.DataObject;

import java.util.UUID;

/**
 * 签到
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CheckInLog extends DataObject {
    private UUID userId;
    private String remark;
}
