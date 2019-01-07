package org.rx.fl.service.dto;

import lombok.Data;

@Data
public class WithdrawResult {
    private String userId;
    private long withdrawAmount;
    private long freezeAmount;
    private boolean hasAliPay;
}
