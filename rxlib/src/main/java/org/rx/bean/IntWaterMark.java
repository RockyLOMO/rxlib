package org.rx.bean;

import lombok.*;

import java.io.Serializable;

import static org.rx.core.Extends.require;

@Getter
@Setter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IntWaterMark implements Serializable {
    private static final long serialVersionUID = -6996645790082139283L;
    private int low, high;

    public void setLow(int low) {
        require(low, low < high);

        this.low = low;
    }

    public void setHigh(int high) {
        require(high, high > low);

        this.high = high;
    }
}
