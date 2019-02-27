package org.rx.test;

import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.common.UserConfig;
import org.rx.fl.service.user.UserNode;
import org.rx.fl.service.user.UserNodeService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@RunWith(SpringRunner.class)
@SpringBootTest
public class UserTests {
    @Resource
    UserConfig userConfig;
    @Resource
    UserNodeService userNodeService;

    @Test
    public void testInit() {
        System.out.println(JSON.toJSONString(userConfig.getGroupAliPayTime()));

//        String id = "c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567";
//        UserNode userLevel = userLevelService.getNode(id);
//        if (!userLevel.isExist()) {
//            userLevel.setPercent(100);
//            userLevelService.create(userLevel);
//        }
//        String subId = "7a69522e-c258-4893-637f-33393d23d2d6";
//        UserNode subLevel = userLevelService.getNode(subId);
//        userLevelService.create(subLevel, userLevel.getId());
    }
}
