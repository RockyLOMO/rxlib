package org.rx.lr.web.dto.user;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.UUID;

@Data
public class CheckInRequest {
    @NotNull
    private UUID userId;
    private String remark;
}
