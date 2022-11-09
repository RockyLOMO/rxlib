package org.rx.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class NEventArgs<T> extends EventArgs {
    private static final long serialVersionUID = 271585610931086708L;
    private T value;
}
