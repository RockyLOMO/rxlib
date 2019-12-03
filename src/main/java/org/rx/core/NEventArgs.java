package org.rx.core;

import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class NEventArgs<T> extends EventArgs {
    private T value;
}
