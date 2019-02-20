package org.rx.fl.dto.repo;

import lombok.Data;
import org.rx.beans.DataRange;

@Data
public class UserDegreeConfig {
    private DataRange<Integer> range;
    private int percent;
}
