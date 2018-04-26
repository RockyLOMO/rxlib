package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
public class CheckInLog extends DataObject {
    private UUID userId;
    private String remark;
}
