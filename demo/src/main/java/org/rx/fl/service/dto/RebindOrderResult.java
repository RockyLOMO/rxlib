package org.rx.fl.service.dto;

import lombok.Data;

@Data
public class RebindOrderResult {
    private String orderNo;
    private long payAmount;
    private long rebateAmount;
    private long balance;
    private long unconfirmedOrderAmount;
}
