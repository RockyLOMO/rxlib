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
import org.rx.*;

import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.Contract.eq;
import static org.rx.Contract.require;

public final class WebCaller extends Disposable {
    private static final ConcurrentMap<String, Set<Cookie>> cookies;
    private static final ChromeDriverService driverService;
    private static final ConcurrentLinkedQueue<ChromeDriver> driverPool;
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
    private ChromeDriver driver;
    private ReentrantLock locker;

    private Lock getLocker() {
        if (locker == null) {
            synchronized (this) {
                if (locker == null) {
                    locker = new ReentrantLock();
                }
            }
        }
        return locker;
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public String getCurrentHandle() {
        return driver.getWindowHandle();
    }

    public String getReadyState() {
        return driver.executeScript("return document.readyState;").toString();
    }

    public WebCaller() {
        driver = driverPool.poll();
        if (driver == null) {
            System.out.println("create driver...");
            ChromeOptions opt = new ChromeOptions();
            opt.setHeadless(isBackground);
            opt.setAcceptInsecureCerts(true);
            opt.addArguments("user-data-dir=" + dataPath + pathCounter++, "disable-infobars",
                    "disable-extensions", "disable-plugins", "disable-java",
                    "no-sandbox", "disable-dev-shm-usage",
                    "disable-web-security");
            opt.setCapability("applicationCacheEnabled", true);
            opt.setCapability("browserConnectionEnabled", true);
            opt.setCapability("hasTouchScreen", true);
            opt.setCapability("networkConnectionEnabled", true);
            driver = new ChromeDriver(driverService, opt);
//            driver.manage().timeouts().implicitlyWait(8 * 1000, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void freeUnmanaged() {
        if (driver == null) {
            return;
        }
        driverPool.add(driver);
        driver = null;
    }

    public void invokeSelf(Consumer<WebCaller> consumer) {
        invokeSelf(consumer, false);
    }

    public void invokeSelf(Consumer<WebCaller> consumer, boolean skipIfLocked) {
        checkNotClosed();
        require(consumer);

        Lock locker = getLocker();
        if (!locker.tryLock()) {
            if (skipIfLocked) {
                return;
            }
            locker.lock();
        }
        try {
            consumer.accept(this);
        } finally {
            locker.unlock();
        }
    }

    public <T> T invokeSelf(Function<WebCaller, T> consumer) {
        return invokeSelf(consumer, false);
    }

    public <T> T invokeSelf(Function<WebCaller, T> consumer, boolean skipIfLocked) {
        checkNotClosed();
        require(consumer);

        Lock locker = getLocker();
        if (!locker.tryLock()) {
            if (skipIfLocked) {
                return null;
            }
            locker.lock();
        }
        try {
            return consumer.apply(this);
        } finally {
            locker.unlock();
        }
    }

    public void invokeNew(Consumer<WebCaller> consumer) {
        checkNotClosed();
        require(consumer);

        try (WebCaller caller = new WebCaller()) {
            consumer.accept(caller);
        }
    }

    public <T> T invokeNew(Function<WebCaller, T> consumer) {
        checkNotClosed();
        require(consumer);

        try (WebCaller caller = new WebCaller()) {
            return consumer.apply(caller);
        }
    }

    public void navigateUrl(String url) {
        navigateUrl(url, null);
    }

    @SneakyThrows
    public void navigateUrl(String url, By locator) {
        checkNotClosed();
        require(url);

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
        WebDriverWait wait = new WebDriverWait(driver, 10);
        wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    public NQuery<String> getAttributeValues(By by, String attrName) {
        checkNotClosed();
        require(by, attrName);

        return findElements(by).select(p -> p.getAttribute(attrName));
    }

    public NQuery<WebElement> findElementsByAttribute(By by, String attrName, String attrVal) {
        checkNotClosed();
        require(by, attrName);

        return findElements(by).where(p -> eq(attrVal, p.getAttribute(attrName)));
    }

    public WebElement findElement(By by) {
        return findElement(by, true);
    }

    public WebElement findElement(By by, boolean throwOnEmpty) {
        checkNotClosed();
        require(by);

        NQuery<WebElement> elements = findElements(by);
        if (!elements.any()) {
            if (throwOnEmpty) {
                throw new InvalidOperationException("Element %s not found", by);
            }
            return null;
        }
        return elements.first();
    }

    public NQuery<WebElement> findElements(By by) {
        return findElements(by, null);
    }

    public NQuery<WebElement> findElements(By by, By waiter) {
        checkNotClosed();
        require(by);

        if (waiter != null) {
            waitElementLocated(waiter);
        }
        return NQuery.of(driver.findElements(by));
    }

    public String openTab() {
        checkNotClosed();

        driver.executeScript("window.open('about:blank','_blank')");
        return NQuery.of(driver.getWindowHandles()).last();
    }

    public void switchTab(String winHandle) {
        checkNotClosed();
        require(winHandle);

        driver.switchTo().window(winHandle);
    }

    public void closeTab(String winHandle) {
        checkNotClosed();
        require(winHandle);

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

    public Object executeScript(String script, Object... args) {
        checkNotClosed();
        require(script);

        return driver.executeScript(script, args);
    }
}
