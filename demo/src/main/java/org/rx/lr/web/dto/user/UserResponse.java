package org.rx.lr.web.dto.user;

import lombok.Data;

import java.util.Date;
import java.util.UUID;

@Data
public class UserResponse {
    private UUID userId;
    private String userName;
    private String email;
    private String mobile;
    private int level;
    private Date createTime;
}
