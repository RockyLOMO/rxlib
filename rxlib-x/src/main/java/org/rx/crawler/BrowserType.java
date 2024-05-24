package org.rx.crawler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum BrowserType {
    CHROME("chrome.exe", "chromedriver.exe"),
    FIRE_FOX("firefox.exe", ""),
    @Deprecated
    IE("iexplore.exe", "IEDriverServer.exe");

    final String processName;
    final String driverName;
}
