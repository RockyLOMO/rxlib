package org.rx.fl.dto.repo;

import lombok.Data;
import org.rx.beans.DateTime;

import javax.validation.constraints.NotNull;

@Data
public class QueryOrdersParameter {
    @NotNull
    private String userId;
    private String orderNo;
    private DateTime startTime;
    private DateTime endTime;
}
