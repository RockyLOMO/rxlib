package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class WithdrawLog implements Serializable {
    private String id;

    private String userId;

    private String balanceLogId;

    private Long amount;

    private Integer status;

    private String remark;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}