package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class User implements Serializable {
    private String id;

    private String nickname;

    private String openId;

    private String alipayName;

    private String alipayAccount;

    private Long balance;

    private Long freezeAmount;

    private Long version;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}
