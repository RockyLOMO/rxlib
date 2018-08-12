package org.rx.lr.web.dto.user;

import lombok.Data;

import java.util.UUID;

@Data
public class SaveUserCommentRequest {
    private String mobile;
    private Integer SmsCode;

    private UUID userId;
    private String content;
}
