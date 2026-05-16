package org.rx.util.rss;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class UserRule implements Serializable {
    private static final long serialVersionUID = -8357195601839740866L;

    public Boolean enabled;
    // 同源地址上游亲和 TTL 秒数，0 表示关闭。
    public int srcSteeringTTL;
    // 有序规则，格式："<目标规则> <动作>"，例如 "srcIp 192.168.31.7 direct"、"dstPort 443 proxy"。
    public List<String> rules;
}
