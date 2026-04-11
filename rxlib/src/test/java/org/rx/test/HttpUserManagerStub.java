package org.rx.test;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Map;

@RequestMapping("js")
public final class HttpUserManagerStub implements HttpUserManager {
    public static final HttpUserManager INSTANCE = new HttpUserManagerStub();

    private HttpUserManagerStub() {
    }

    @Override
    public int computeLevel(int x, int y) {
        return x + y;
    }

    @Override
    @RequestMapping(value = "on", method = RequestMethod.GET)
    public Map<String, Object> queryIp() {
        return null;
    }
}
