package org.rx.fl.dto.repo;

import lombok.Data;

import java.io.Serializable;

@Data
public class RebindOrderResult implements Serializable {
    private String orderNo;
    private long payAmount;
    private long rebateAmount;
    private long balance;
    private long unconfirmedOrderAmount;
}
