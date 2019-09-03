package org.rx.core;

import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@RequiredArgsConstructor
public class NEventArgs<T> extends EventArgs {
    private final T value;
}
