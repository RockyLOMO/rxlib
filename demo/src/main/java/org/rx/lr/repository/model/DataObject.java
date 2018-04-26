package org.rx.lr.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@Data
public abstract class DataObject implements Serializable {
    private UUID id;
    private Date createTime, modifyTime;
}
