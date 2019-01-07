package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class UserGoods implements Serializable {
    private String id;

    private String userId;

    private Integer mediaType;

    private String goodsId;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}