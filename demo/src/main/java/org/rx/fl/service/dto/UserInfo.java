package org.rx.fl.service.dto;

import lombok.Data;

@Data
public class UserInfo {
    private String userId;
    private long balance;
    private long totalCheckInCount;
    private long totalCheckInAmount;
}
