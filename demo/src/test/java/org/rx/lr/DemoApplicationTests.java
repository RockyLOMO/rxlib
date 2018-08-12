package org.rx.lr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.lr.utils.SmsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class DemoApplicationTests {
    @Autowired
    private SmsUtil smsUtil;

    @Test
    public void testSms() {
        System.out.println(smsUtil.sendSms("17091916400", "您的验证码是520"));
    }
}
