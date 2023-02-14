package org.rx.test.bean;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Map;

@RequestMapping("js")
public interface HttpUserManager {
    HttpUserManager INSTANCE = new HttpUserManager() {
        @Override
        public int computeLevel(int x, int y) {
            return x + y;
        }

        @Override
        public Map<String, Object> queryIp() {
            return null;
        }
    };

    int computeLevel(int x, int y);

    @RequestMapping(value = "on", method = RequestMethod.GET)
    Map<String, Object> queryIp();
}
