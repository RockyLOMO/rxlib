package org.rx.fl.dto.repo;

import lombok.Data;

import java.io.Serializable;

@Data
public class WithdrawResult implements Serializable {
    private String userId;
    private long withdrawAmount;
    private long freezeAmount;
    private boolean hasAliPay;
}
