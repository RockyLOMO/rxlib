package org.rx.fl.dto.repo;

import lombok.Data;
import org.rx.beans.DataRange;

import java.io.Serializable;

@Data
public class UserDegreeConfig implements Serializable {
    private DataRange<Integer> range;
    private int percent;
}
