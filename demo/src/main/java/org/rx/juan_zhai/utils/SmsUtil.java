package org.rx.juan_zhai.utils;

import com.alibaba.fastjson.JSONObject;
import com.github.qcloudsms.SmsBase;
import com.github.qcloudsms.SmsSenderUtil;
import com.github.qcloudsms.SmsSingleSenderResult;
import com.github.qcloudsms.httpclient.DefaultHTTPClient;
import com.github.qcloudsms.httpclient.HTTPMethod;
import com.github.qcloudsms.httpclient.HTTPRequest;
import com.github.qcloudsms.httpclient.HTTPResponse;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

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

//    @SneakyThrows
//    public SmsSingleSenderResult sendSms(String mobile, String msg) {
//        SmsSingleSender sender = new SmsSingleSender(appId, appKey);
//        return sender.send(0, "86", mobile, msg, "", "");
//    }

    @SneakyThrows
    public SmsSingleSenderResult sendSms(String mobile, int templateId, String... param) {
        RxSmsSender sender = new RxSmsSender(appId, appKey);
        //smsSign
        return sender.sendWithParam("86", mobile, templateId, Arrays.asList(param), "", "", "");
    }

    private class RxSmsSender extends SmsBase {
        private String url = "https://yun.tim.qq.com/v5/tlssmssvr/sendsms";

        public RxSmsSender(int appid, String appkey) {
            super(appid, appkey, new DefaultHTTPClient());
        }

        @SneakyThrows
        public SmsSingleSenderResult sendWithParam(String nationCode, String phoneNumber, int templateId, List<String> params, String sign, String extend, String ext) {
            long random = SmsSenderUtil.getRandom();
            long now = SmsSenderUtil.getCurrentTime();
            JSONObject body = new JSONObject();
            JSONObject telBody = new JSONObject();
            telBody.put("nationcode", nationCode);
            telBody.put("mobile", phoneNumber);
            body.put("tel", telBody);
            body.put("sig", SmsSenderUtil.calculateSignature(this.appkey, random, now, phoneNumber));
            body.put("tpl_id", templateId);
            body.put("params", params);
            body.put("sign", sign);
            body.put("time", now);
            body.put("extend", SmsSenderUtil.isNotEmpty(extend) ? extend : "");
            body.put("ext", SmsSenderUtil.isNotEmpty(ext) ? ext : "");
            HTTPRequest req = (new HTTPRequest(HTTPMethod.POST, this.url)).addHeader("Conetent-Type", "application/json").addQueryParameter("sdkappid", this.appid).addQueryParameter("random", random).setConnectionTimeout(60000).setRequestTimeout(60000).setBody(body.toString());

            try {
                HTTPResponse res = this.httpclient.fetch(req);
                this.handleError(res);
                return (new SmsSingleSenderResult()).parseFromHTTPResponse(res);
            } catch (URISyntaxException var15) {
                throw new RuntimeException("API url has been modified, current url: " + this.url);
            }
        }
    }
}
