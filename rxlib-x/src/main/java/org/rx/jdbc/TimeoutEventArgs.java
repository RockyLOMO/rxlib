package org.rx.jdbc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.EventArgs;

@Data
@EqualsAndHashCode(callSuper = true)
public class TimeoutEventArgs extends EventArgs {
    final long executeTimeoutMillis;
    final String sql;
    final Object[] parameters;
}
