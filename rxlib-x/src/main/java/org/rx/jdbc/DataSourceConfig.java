package org.rx.jdbc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class DataSourceConfig implements Serializable {
    private static final long serialVersionUID = 8722778295417630020L;
    String jdbcUrl;
    String username, password;
}
