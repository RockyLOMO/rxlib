package org.rx.crawler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum BrowserType {
    CHROME("chrome.exe", "chromedriver.exe"),
    IE("iexplore.exe", "IEDriverServer.exe");

    private final String processName;
    private final String driverName;
}
