package org.rx.lr.utils;

import com.github.qcloudsms.SmsSingleSender;
import com.github.qcloudsms.SmsSingleSenderResult;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmsUtil {
    // 短信应用SDK AppID
    @Value("${app.sms.appId}")
    int appId;
    // 短信应用SDK AppKey
    @Value("${app.sms.appKey}")
    String appKey;
    // 签名
    @Value("${app.sms.smsSign}")
    String smsSign;

//    public void sendSignUpSMS(String mobile) {
//        sendSms(0, mobile);
//    }

    @SneakyThrows
    public SmsSingleSenderResult sendSms(String mobile, String msg) {
        SmsSingleSender sender = new SmsSingleSender(appId, appKey);
        return sender.send(0, "86", mobile, msg, "", "");
    }

    @SneakyThrows
    public SmsSingleSenderResult sendSms(String mobile, int templateId, String... param) {
        SmsSingleSender sender = new SmsSingleSender(appId, appKey);
        return sender.sendWithParam("86", mobile, templateId, param, smsSign, "", "");
    }
}
