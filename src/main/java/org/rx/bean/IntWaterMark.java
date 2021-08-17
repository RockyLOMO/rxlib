package org.rx.bean;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

import static org.rx.core.App.require;

@Data
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
