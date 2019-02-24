package org.rx.fl.dto.repo;

import lombok.Data;
import org.rx.beans.DateTime;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class QueryOrdersParameter implements Serializable {
    @NotNull
    private String userId;
    private String orderNo;
    private DateTime startTime;
    private DateTime endTime;
}
