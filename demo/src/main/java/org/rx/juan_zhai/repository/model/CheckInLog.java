package org.rx.juan_zhai.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.DataObject;

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
