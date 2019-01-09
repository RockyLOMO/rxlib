package org.rx.juan_zhai.web.dto.user;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class SignInRequest {
    @NotNull
    private String userName;

    @NotNull
    private String password;
}
