package org.rx.fl.service.dto;

import lombok.Data;

@Data
public class UserInfo {
    private String userId;
    private long balance;
    private long freezeAmount;
    private long totalWithdrawAmount;
    private long withdrawingAmount;
    private long checkInCount;
    private long checkInAmount;
    private long unconfirmedOrderAmount;
    private long confirmedOrderCount;
}
