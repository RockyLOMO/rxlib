package org.rx.fl.util;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.json.JSONObject;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rx.App;
import org.rx.Logger;
import org.rx.NQuery;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import static org.rx.Contract.eq;
import static org.rx.Contract.require;

public final class WebCaller implements AutoCloseable {
    private static final ConcurrentMap<String, Set<Cookie>> cookies;
    private static final ChromeDriverService driverService;
    private static final ConcurrentLinkedQueue<ChromeDriver> driverPool;
    private static final String FuncFormat = "function(){%s}";
    private static final String dataPath = (String) App.readSetting("app.web.dataPath");
    private static volatile int pathCounter;

    static {
        cookies = new ConcurrentHashMap<>();
        System.setProperty("webdriver.chrome.driver", (String) App.readSetting("app.web.driver"));
        driverService = new ChromeDriverService.Builder().build();
        driverPool = new ConcurrentLinkedQueue<>();
    }

    public synchronized static void releaseAll() {
        for (ChromeDriver driver : NQuery.of(driverPool).toList()) {
            driver.quit();
        }
        driverPool.clear();
    }

    @Getter
    @Setter
    private boolean isBackground;
    @Getter
    @Setter
    private boolean isShareCookie;
    @Getter
    @Setter
    private boolean enableImage;
    private ChromeDriver driver;

    private ChromeDriver getDriver() {
        if (driver == null) {
            System.out.println("create driver...");
            ChromeOptions opt = new ChromeOptions();
            opt.setHeadless(isBackground);
            opt.setAcceptInsecureCerts(true);
            opt.addArguments("user-data-dir=" + dataPath + pathCounter++, "disable-infobars",
//                    "disable-extensions", "disable-plugins", "disable-java",
//                    "no-sandbox", "disable-dev-shm-usage",
                    "disable-web-security", "ash-enable-unified-desktop");
            if (!enableImage) {
                opt.addArguments("disable-images");
            }
            opt.setCapability("applicationCacheEnabled", true);
            opt.setCapability("browserConnectionEnabled", true);
            opt.setCapability("hasTouchScreen", true);
            opt.setCapability("networkConnectionEnabled", true);
            opt.setCapability("strictFileInteractability", true);
            driver = new ChromeDriver(driverService, opt);
//            driver.manage().timeouts().implicitlyWait(8 * 1000, TimeUnit.MILLISECONDS);
        }
        return driver;
    }

    public String getCurrentUrl() {
        return getDriver().getCurrentUrl();
    }

    public String getCurrentHandle() {
        return getDriver().getWindowHandle();
    }

    public WebCaller() {
        enableImage = true;
        driver = driverPool.poll();
    }

    @Override
    public void close() {
        if (driver == null) {
            return;
        }
        driverPool.add(driver);
        driver = null;
    }

    public void navigateUrl(String url) {
        navigateUrl(url, null);
    }

    @SneakyThrows
    public void navigateUrl(String url, By locator) {
        require(url);

        ChromeDriver driver = getDriver();
        if (isShareCookie) {
            WebDriver.Options manage = driver.manage();
            try {
                String host = new URL(url).getHost();
                Set<Cookie> set = cookies.get(host);
                for (Cookie p : set) {
                    Logger.info("%s load cookie: " + p.getDomain() + "-" + p.getName() + "=" + p.getValue(), host);
                    manage.addCookie(p);
                }
            } catch (UnableToSetCookieException e) {
                System.out.println(e.getMessage());
            }
        }
        driver.get(url);

//        String status;
//        while (!"complete".equals(status = driver.executeScript("return document.readyState").toString())) {
//            Thread.sleep(100);
//            Logger.info("navigateUrl %s %s", url, status);
//        }
//        Logger.info("navigateUrl %s %s", url, status);

        if (locator != null) {
            waitElementLocated(locator);
        }
        if (isShareCookie) {
            String host = new URL(getCurrentUrl()).getHost();
            Set<Cookie> set = cookies.get(host);
            if (set == null) {
                set = Collections.synchronizedSet(new HashSet<>());
            }
            WebDriver.Options manage = driver.manage();
            set.addAll(manage.getCookies());
            cookies.put(host, set);
        }
    }

    private void waitElementLocated(By locator) {
        WebDriverWait wait = new WebDriverWait(getDriver(), 10);
        wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    public NQuery<String> getAttributeValues(By by, String attrName) {
        require(by, attrName);

        return findElements(by).select(p -> p.getAttribute(attrName));
    }

    public NQuery<WebElement> findElementsByAttribute(By by, String attrName, String attrVal) {
        require(by, attrName);

        return findElements(by).where(p -> eq(attrVal, p.getAttribute(attrName)));
    }

    public NQuery<WebElement> findElements(By by) {
        return findElements(by, null);
    }

    public NQuery<WebElement> findElements(By by, By waiter) {
        require(by);

        if (waiter != null) {
            waitElementLocated(waiter);
        }
        return NQuery.of(getDriver().findElements(by));
    }

    public String openTab() {
        ChromeDriver driver = getDriver();
        driver.executeScript("window.open('about:blank','_blank')");
        return NQuery.of(driver.getWindowHandles()).last();
    }

    public void switchTab(String winHandle) {
        require(winHandle);

        getDriver().switchTo().window(winHandle);
    }

    public void closeTab(String winHandle) {
        require(winHandle);

        ChromeDriver driver = getDriver();
        String current = driver.getWindowHandle();
        boolean isSelf = current.equals(winHandle);
        if (!isSelf) {
            switchTab(winHandle);
        }
        driver.close();
        if (isSelf) {
            current = NQuery.of(driver.getWindowHandles()).first();
        }
        switchTab(current);
    }

//    public WebElement findElementById(String id) {
//        try {
//            return driver.findElementById(id);
//        } catch (NoSuchElementException e) {
//            Logger.info("findElementById(%s) null", id);
//            return null;
//        }
//    }
//
//    public NQuery<WebElement> findElementsByName(String name) {
//        try {
//            return NQuery.of(driver.findElementsByName(name));
//        } catch (NoSuchElementException e) {
//            Logger.info("findElementsByName(%s) null", name);
//            return NQuery.of();
//        }
//    }
//
//    public NQuery<WebElement> findElementsByCssSelector(String css) {
//        try {
//            return NQuery.of(driver.findElementsByCssSelector(css));
//        } catch (NoSuchElementException e) {
//            Logger.info("findElementsByCssSelector(%s) null", css);
//            return NQuery.of();
//        }
//    }
//
//    public String getFunc(String script) {
//        return String.format(FuncFormat, script);
//    }
}
