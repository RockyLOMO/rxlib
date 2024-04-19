package org.rx.crawler;

import org.rx.core.FluentWait;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.io.IOStream;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

public interface Browser extends AutoCloseable {
    String BLANK_URL = "about:blank", BODY_SELECTOR = "body";

    static String readResourceJs(String resourcePath) {
        return IOStream.readString(Reflects.getResource(resourcePath), StandardCharsets.UTF_8);
    }

    BrowserType getType();

    void setCookieRegion(String cookieRegion);

    long getWaitMillis();

    String getCurrentUrl();

    default FluentWait createWait(int timeoutSeconds) {
        return FluentWait.polling(timeoutSeconds * 1000L, getWaitMillis());
    }

    default void navigateBlank() {
        nativeGet(BLANK_URL);
    }

    void navigateUrl(String url) throws TimeoutException;

    void navigateUrl(String url, String locatorSelector) throws TimeoutException;

    void navigateUrl(String url, String locatorSelector, int timeoutSeconds) throws TimeoutException;

    void nativeGet(String url);

    String saveCookies(boolean reset) throws TimeoutException;

    void clearCookies(boolean onlyBrowser);

    void setRawCookie(String rawCookie);

    String getRawCookie();

    /**
     * 基本的selector，不能包含:eq(1)等
     *
     * @param selector
     * @return
     */
    boolean hasElement(String selector);

    String elementText(String selector);

    Linq<String> elementsText(String selector);

    String elementVal(String selector);

    Linq<String> elementsVal(String selector);

    String elementAttr(String selector, String... attrArgs);

    Linq<String> elementsAttr(String selector, String... attrArgs);

    void elementClick(String selector);

    void elementClick(String selector, boolean waitElementLocated);

    void elementPress(String selector, String keys);

    void elementPress(String selector, String keys, boolean waitElementLocated);

    void waitElementLocated(String selector) throws TimeoutException;

    void waitElementLocated(String selector, int timeoutSeconds) throws TimeoutException;

    void injectScript(String script);

    //Boolean, Long, String, List, WebElement
    <T> T executeScript(String script, Object... args);

    <T> T injectAndExecuteScript(String injectScript, String script, Object... args);

    <T> T executeConfigureScript(String scriptName, Object... args);

    byte[] screenshotAsBytes(String selector);

    void focus();

    void maximize();

    void normalize();
}
