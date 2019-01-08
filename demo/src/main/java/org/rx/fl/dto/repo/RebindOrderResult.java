package org.rx.fl.dto.repo;

import lombok.Data;

@Data
public class RebindOrderResult {
    private String orderNo;
    private long payAmount;
    private long rebateAmount;
    private long balance;
    private long unconfirmedOrderAmount;
}
