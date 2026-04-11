package org.rx.test;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Map;

@RequestMapping("js")
public interface HttpUserManager {
    HttpUserManager INSTANCE = HttpUserManagerStub.INSTANCE;

    int computeLevel(int x, int y);

    @RequestMapping(value = "on", method = RequestMethod.GET)
    Map<String, Object> queryIp();
}
