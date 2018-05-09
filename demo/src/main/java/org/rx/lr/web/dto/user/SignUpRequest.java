package org.rx.lr.web.dto.user;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class SignUpRequest {
    @NotNull
    private String userName;

    @NotNull
    private String password;

    private String email;

    private String mobile;
}
