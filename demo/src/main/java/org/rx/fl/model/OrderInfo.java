package org.rx.fl.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class OrderInfo implements Serializable {
    private String orderNo;
    private String goodsId;
    private String goodsName;
    private String unitPrice;
    private int quantity;
    private String sellerName;
    private String payAmount;
    private String rebateAmount;
    private String status;
    private Date createTime;
}
