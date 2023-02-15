package org.rx.core;

import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class NEventArgs<T> extends EventArgs {
    private static final long serialVersionUID = 271585610931086708L;
    private T value;
}
