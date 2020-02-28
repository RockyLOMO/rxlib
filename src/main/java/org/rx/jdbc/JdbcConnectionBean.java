package org.rx.jdbc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class JdbcConnectionBean implements Serializable {
    private int idleConnections;
    private int activeConnections;
    private int totalConnections;
    private int threadsAwaitingConnection;
}
