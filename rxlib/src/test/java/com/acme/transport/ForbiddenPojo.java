package com.acme.transport;

import java.io.Serializable;

public final class ForbiddenPojo implements Serializable {
    private static final long serialVersionUID = -7250101239427176915L;
    public final String name;

    public ForbiddenPojo(String name) {
        this.name = name;
    }
}
