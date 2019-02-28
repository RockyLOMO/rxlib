package org.rx.fl.dto.media;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Date;

@Data
public class OrderInfo implements Serializable {
    @NotNull
    private MediaType mediaType;
    @NotNull
    private String orderNo;
    @NotNull
    private String goodsId;
    @NotNull
    private String goodsName;
    private String unitPrice;
    private int quantity;
    private String sellerName;
    private String promotionId;
    private String payAmount;
    @NotNull
    private String rebateAmount;
    private String settleAmount;
    @NotNull
    private OrderStatus status;
    @NotNull
    private Date createTime;
}
