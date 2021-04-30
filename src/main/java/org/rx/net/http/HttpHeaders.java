package org.rx.net.http;

public class HttpHeaders extends org.springframework.http.HttpHeaders {
    public void setUserAgent(String val) {
        set(USER_AGENT, val);
    }

    public void setCookie(String val) {
        set(COOKIE, val);
    }
}
