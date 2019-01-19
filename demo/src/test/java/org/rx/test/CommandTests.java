package org.rx.test;

import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.fl.dto.repo.OrderResult;
import org.rx.fl.dto.repo.QueryOrdersParameter;
import org.rx.fl.service.OrderService;
import org.rx.fl.service.command.CommandManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.common.Contract.toJsonString;

@RunWith(SpringRunner.class)
@SpringBootTest
public class CommandTests {
    @Resource
    CommandManager commandManager;
    @Resource
    OrderService orderService;

    @SneakyThrows
    @Test
    public void handleFindAdv() {
        String userId = "4c3d0808-2480-3a17-837d-e6fe068c1a0a";
        String message = "【卡通羊羔绒宝宝绒四件套秋冬立体贴布绣花儿童被子双面绒恐龙被套】https://m.tb.cn/h.3ruLcuh 点击链接，再选择浏览器咑閞；或復·制这段描述￥P1ScbKlP0Lp￥后到淘♂寳♀[来自超级会员的分享]";
        String content = commandManager.handleMessage(userId, message);
        System.out.println(content);
    }

    @Test
    public void queryOrder() {
        QueryOrdersParameter parameter = new QueryOrdersParameter();
        parameter.setUserId("c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567");
        List<OrderResult> orders = orderService.queryOrders(parameter);
        System.out.println(toJsonString(orders));
    }
}
