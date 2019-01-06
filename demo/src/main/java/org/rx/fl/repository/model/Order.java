package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class Order implements Serializable {
    private String id;

    private String userId;

    private Integer mediaType;

    private String orderNo;

    private String goodsId;

    private String goodsName;

    private Long unitPrice;

    private Integer quantity;

    private String sellerName;

    private Long payAmount;

    private Long rebateAmount;

    private Integer status;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}
