package org.rx.test.juan_zhai;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.fl.repository.UserMapper;
import org.rx.juan_zhai.utils.SmsUtil;
import org.rx.util.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class ApplicationTests {
    @Autowired
    private SmsUtil smsUtil;

    @Test
    public void testfl() {
        UserMapper mapper = SpringContextUtil.getBean(UserMapper.class);
        System.out.println(mapper == null);
    }

    @Test
    public void testSms() {
//        System.out.println(smsUtil.sendSms("17091916400", "您的验证码是520"));
        System.out.println(smsUtil.sendSms("17091916400", 173531, "520"));
    }
}
