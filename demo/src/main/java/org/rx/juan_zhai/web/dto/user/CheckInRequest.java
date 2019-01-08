package org.rx.juan_zhai.web.dto.user;

import lombok.Data;

import javax.validation.Contract.aints.NotNull;
import java.util.UUID;

@Data
public class CheckInRequest {
    @NotNull
    private UUID userId;
    private String remark;
}
