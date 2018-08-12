package org.rx.lr.service;

import com.github.qcloudsms.SmsSingleSenderResult;
import com.google.common.base.Strings;
import lombok.Data;
import lombok.SneakyThrows;
import org.rx.App;
import org.rx.Description;
import org.rx.Logger;
import org.rx.SystemException;
import org.rx.lr.utils.SmsUtil;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotNull;
import java.util.concurrent.ThreadLocalRandom;

@EnableValid
@Service
public class SmsService {
    public enum CodeType {
        @Description("【隽斋】您的验证码是%s")
        UserComment
    }

    @Data
    public class SmsCounter {
        static final int MaxCount = 4, MaxErrorCount = 2;
        private int count = 1, errorCount;
        private final String mobile;
        private Integer code;

        public SmsCounter(String mobile) {
            this.mobile = mobile;
        }

        public boolean preCheck(int code) {
            if (count >= MaxCount) {
                return false;
            }
            this.code = code;
            return true;
        }

        public boolean check(int code) {
            boolean r = this.code != null && this.code == code;
            if (!r) {
                if (errorCount < MaxErrorCount) {
                    errorCount++;
                } else {
                    this.code = null;
                }
                return false;
            }
            this.code = null;
            return true;
        }
    }

    @Autowired
    private SmsUtil smsUtil;

    @SneakyThrows
    public void sendCode(@NotNull String mobile, @NotNull CodeType type) {
        int code = ThreadLocalRandom.current().nextInt(1000, 9999);
        SmsCounter counter = App.getOrStore(getKey(mobile), k -> new SmsCounter(mobile));
        if (!counter.preCheck(code)) {
            return;
        }
        //todo app
        Description desc = CodeType.class.getField(type.name()).getAnnotation(Description.class);
        SmsSingleSenderResult result = smsUtil.sendSms(mobile, String.format(desc.value(), code));
        if (Strings.isNullOrEmpty(result.errMsg)) {
            counter.count++;
        }
        Logger.info("sendCode thirdResult=%s, counter=%s", result, counter);
    }

    public void validCode(@NotNull String mobile, int code) {
        SmsCounter counter = App.getOrStore(getKey(mobile), k -> new SmsCounter(mobile));
        if (!counter.check(code)) {
            throw SystemException.wrap(new IllegalArgumentException("手机验证码错误"));
        }
    }

    private String getKey(String mobile) {
        return String.format("SmsService-%s", mobile);
    }
}
