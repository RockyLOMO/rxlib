package org.rx.util.rss;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.net.InetAddress;
import java.util.Set;

@Getter
@Setter
@ToString
public class RouteConf {
    public boolean enable;
    public Set<String> dstGeoSiteDirectRules;
    public Set<InetAddress> srcIpProxyRules;
    public int srcSteeringTTL;
}
