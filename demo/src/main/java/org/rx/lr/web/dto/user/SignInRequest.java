package org.rx.lr.web.dto.user;

import lombok.Data;

@Data
public class SignInRequest {
    private String userName;
    private String password;
}
