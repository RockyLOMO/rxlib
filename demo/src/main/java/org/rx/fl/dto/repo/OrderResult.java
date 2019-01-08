package org.rx.fl.dto.repo;

import lombok.Data;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderStatus;

import java.util.Date;

@Data
public class OrderResult {
    private MediaType mediaType;
    private String orderNo;
    private String goodsName;
    private long rebateAmount;
    private OrderStatus status;
    private Date createTime;
}
