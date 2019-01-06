package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class BalanceLog implements Serializable {
    private String id;

    private String userId;

    private Integer type;

    private Integer source;

    private String sourceId;

    private String clientIp;

    private Long preBalance;

    private Long postBalance;

    private Long value;

    private String remark;

    private Long version;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}
