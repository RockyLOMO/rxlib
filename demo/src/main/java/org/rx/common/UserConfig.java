package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.user")
public class UserConfig {
    private String[] adminIds;
    private int heartbeatMinutes;
    private String[] relations;
    private String dataFilePath;
    private String intro;
    private String aliPayCode;
    private String[] groupAliPay, groupAliPayTime;
    private String[] groupGoods, groupGoodsTime, groupGoodsName;
}
