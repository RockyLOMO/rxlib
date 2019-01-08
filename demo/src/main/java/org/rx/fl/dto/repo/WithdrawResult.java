package org.rx.fl.dto.repo;

import lombok.Data;

@Data
public class WithdrawResult {
    private String userId;
    private long withdrawAmount;
    private long freezeAmount;
    private boolean hasAliPay;
}
