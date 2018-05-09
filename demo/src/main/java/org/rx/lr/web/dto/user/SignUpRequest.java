package org.rx.lr.web.dto.user;

import lombok.Data;

@Data
public class SignUpRequest {
    private String userName;
    private String password;
    private String email;
    private String mobile;
}
