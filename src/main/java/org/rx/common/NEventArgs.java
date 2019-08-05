package org.rx.common;

import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@RequiredArgsConstructor
public class NEventArgs<T> extends EventArgs {
    private final T value;
}
