package org.rx.jdbc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class ConnectionPoolMXBean implements Serializable {
    private static final long serialVersionUID = -8774671998907063174L;
    private String name;
    private int idleConnections;
    private int activeConnections;
    private int totalConnections;
    private int threadsAwaitingConnection;
}
