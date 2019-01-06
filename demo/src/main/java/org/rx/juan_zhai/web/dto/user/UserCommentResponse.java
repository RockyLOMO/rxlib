package org.rx.juan_zhai.web.dto.user;

import lombok.Data;

import java.util.Date;
import java.util.UUID;

@Data
public class UserCommentResponse {
    private UUID userId;
    private UUID userCommentId;
    private String mobile;
    private String content;
    private Date createTime, modifyTime;
}
