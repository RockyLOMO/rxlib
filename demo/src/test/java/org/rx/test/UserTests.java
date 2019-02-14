package org.rx.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.fl.service.user.UserNode;
import org.rx.fl.service.user.UserNodeService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@RunWith(SpringRunner.class)
@SpringBootTest
public class UserTests {
    @Resource
    UserNodeService userLevelService;

    @Test
    public void testInit() {
        String id = "c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567";
        UserNode userLevel = userLevelService.getNode(id);
        if (!userLevel.isSave()) {
            userLevel.setPercent(100);
            userLevelService.create(userLevel);
        }
        String subId = "7a69522e-c258-4893-637f-33393d23d2d6";
        UserNode subLevel = userLevelService.getNode(subId);
        userLevelService.create(subLevel, userLevel.getId());
    }
}
