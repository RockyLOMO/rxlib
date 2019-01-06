package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class CheckInLog implements Serializable {
    private String id;

    private String userId;

    private String clientIp;

    private Long bonus;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}
