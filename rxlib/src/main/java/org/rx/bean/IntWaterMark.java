package org.rx.bean;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class IntWaterMark implements Serializable {
    private static final long serialVersionUID = -6996645790082139283L;
    private int low, high;
}
