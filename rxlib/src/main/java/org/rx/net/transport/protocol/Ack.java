package org.rx.net.transport.protocol;

import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@RequiredArgsConstructor
public class Ack implements Serializable {
    private static final long serialVersionUID = -54864986566165671L;
    public final int id;
}
