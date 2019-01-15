package org.rx.fl.dto.repo;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UserDto {
    @NotNull
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
