package org.rx.test.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.EventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
public class AuthEventArgs extends EventArgs {
    private int flag;
}
