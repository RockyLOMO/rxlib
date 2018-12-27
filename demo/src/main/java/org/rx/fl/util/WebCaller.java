package org.rx.fl.util;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rx.App;
import org.rx.NQuery;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.Contract.eq;
import static org.rx.Contract.require;

public final class WebCaller implements AutoCloseable {
    private static Set<Cookie> cookies;
    private static final ConcurrentLinkedQueue<ChromeDriver> Drivers = new ConcurrentLinkedQueue<>();
    private static final String FuncFormat = "function(){%s}";
    static ChromeDriverService driverService;

    static {
        System.setProperty("webdriver.chrome.driver", (String) App.readSetting("app.web.driver"));
        driverService = new ChromeDriverService.Builder().withSilent(true).build();
    }

    public synchronized static void releaseAll() {
        for (ChromeDriver driver : Drivers) {
            driver.close();
        }
        Drivers.clear();
    }

    private ChromeDriver driver;
    @Getter
    @Setter
    private boolean isMain;
    static int i=0;

    public WebCaller() {
        driver = Drivers.poll();
        if (driver == null) {
            ChromeOptions opt = new ChromeOptions();
//            opt.setHeadless(true);
            opt.addArguments("disable-infobars", "--disable-extensions","--disable-dev-shm-usage","--profile-directory=\"Profile "+(i++)+"\"",
                    "--no-sandbox","disable-web-security", "user-data-dir=D:\\rx2", "ash-enable-unified-desktop");
            opt.setAcceptInsecureCerts(true);
            opt.setCapability("applicationCacheEnabled", true);
            opt.setCapability("browserConnectionEnabled", true);
            opt.setCapability("hasTouchScreen", true);
            opt.setCapability("networkConnectionEnabled", true);
            opt.setCapability("strictFileInteractability", true);
            driver = new ChromeDriver( opt);
//            driver.manage().timeouts().implicitlyWait(8 * 1000, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        Drivers.add(driver);
        driver = null;
    }

    public void navigateUrl(String url) {
        navigateUrl(url, null);
    }

    @SneakyThrows
    public void navigateUrl(String url, By locator) {
        require(url);

        if (!isMain && !CollectionUtils.isEmpty(cookies)) {
            WebDriver.Options manage = driver.manage();
            try {
                for (Cookie p : cookies) {
                    System.out.println("load cookie: " + p.getDomain() + "-" + p.getName() + "=" + p.getValue());
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
            WebDriverWait wait = new WebDriverWait(driver, 20);
            wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        }
        if (isMain) {
            WebDriver.Options manage = driver.manage();
            cookies = manage.getCookies();
        }
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
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
        require(by);

        return NQuery.of(driver.findElements(by));
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
