package org.rx.net.http;

import lombok.Getter;
import org.rx.exception.ApplicationException;

public class RestClientException extends org.springframework.web.client.RestClientException {
    @Getter
    private final String fullLogMessage;

    public RestClientException(String fullLogMessage) {
        this(fullLogMessage, null);
    }

    public RestClientException(String fullLogMessage, Throwable ex) {
        super(ApplicationException.getMessage(ex), ex);
        this.fullLogMessage = fullLogMessage;
    }
}
