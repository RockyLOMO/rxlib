package org.rx.lr.web.dto.user;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.NotNull;

@Data
public class SignUpRequest {
    @NotNull
    @Length(min = 4, max = 12)
    private String userName;

    @NotNull
    @Length(min = 8, max = 12)
    private String password;

    private String email;

    private String mobile;
}
