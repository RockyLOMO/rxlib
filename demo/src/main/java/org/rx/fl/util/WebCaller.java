package org.rx.fl.util;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rx.*;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.Contract.eq;
import static org.rx.Contract.require;

@Slf4j
public final class WebCaller extends Disposable {
    private static final ChromeDriverService driverService;
    private static final ConcurrentLinkedQueue<ChromeDriver> driverPool;
    private static final String dataPath = (String) App.readSetting("app.chrome.dataPath");
    private static volatile int pathCounter;

    static {
        System.setProperty("webdriver.chrome.driver", (String) App.readSetting("app.chrome.driver"));
        driverService = new ChromeDriverService.Builder()
                .usingAnyFreePort()
                .withLogFile(new File((String) App.readSetting("app.chrome.logPath")))
                .withVerbose(true).build();
        driverPool = new ConcurrentLinkedQueue<>();
        Integer init = (Integer) App.readSetting("app.chrome.initSize");
        if (init != null) {
            init(init);
        }
    }

    private static ChromeDriver create(boolean fromPool) {
        ChromeDriver driver = null;
        if (fromPool) {
            driver = driverPool.poll();
        }
        if (driver == null) {
            log.info("create driver...");
            ChromeOptions opt = new ChromeOptions();
            opt.setHeadless((boolean) App.readSetting("app.chrome.isBackground"));
            opt.setAcceptInsecureCerts(true);
            opt.setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

            opt.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true);
            opt.setCapability(CapabilityType.SUPPORTS_ALERTS, false);
            opt.setCapability(CapabilityType.SUPPORTS_APPLICATION_CACHE, true);
            opt.setCapability(CapabilityType.SUPPORTS_NETWORK_CONNECTION, true);
            opt.setCapability("browserConnectionEnabled", true);

            Map<String, Object> chromePrefs = new HashMap<>();
            String downloadPath = (String) App.readSetting("app.chrome.downloadPath");
            App.createDirectory(downloadPath);
            chromePrefs.put("download.default_directory", downloadPath);
            chromePrefs.put("profile.default_content_settings.popups", 0);
            chromePrefs.put("pdfjs.disabled", true);
            opt.setExperimentalOption("prefs", chromePrefs);

            opt.addArguments("no-first-run", "homepage=about:blank", "window-size=1024,800",
                    "disable-infobars", "disable-web-security", "ignore-certificate-errors", "allow-running-insecure-content",
                    "disable-accelerated-video", "disable-java", "disable-plugins", "disable-plugins-discovery", "disable-extensions",
                    "disable-desktop-notifications", "disable-speech-input", "disable-translate", "safebrowsing-disable-download-protection", "no-pings",
                    "ash-force-desktop", "disable-background-mode", "no-sandbox", "test-type=webdriver");
//            opt.addArguments("window-position=", "disable-dev-shm-usage");
            if (!Strings.isNullOrEmpty(dataPath)) {
                opt.addArguments("user-data-dir=" + dataPath + pathCounter++, "restore-last-session");
            }

            driver = new ChromeDriver(driverService, opt);
//        driver.manage().timeouts().implicitlyWait(8 * 1000, TimeUnit.MILLISECONDS);
//            driver.get("http://www.baidu.com");
        }
        return driver;
    }

    private static void release(ChromeDriver driver) {
        driverPool.add(driver);
    }

    public static void init(int count) {
        int left = count - driverPool.size();
        for (int i = 0; i < left; i++) {
            release(create(false));
        }
    }

    public synchronized static void purgeAll() {
        for (ChromeDriver driver : NQuery.of(driverPool).toList()) {
            driver.quit();
        }
        driverPool.clear();
    }

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
        driver = create(true);
    }

    @Override
    protected void freeUnmanaged() {
        if (driver == null) {
            return;
        }
        release(driver);
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
                Set<Cookie> set = HttpCaller.CookieContainer.loadForRequest(url);
                for (Cookie p : set) {
                    log.debug("{} load cookie: " + p.getDomain() + " / " + p.getName() + "=" + p.getValue(), host);
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
            WebDriver.Options manage = driver.manage();
            HttpCaller.CookieContainer.saveFromResponse(getCurrentUrl(), manage.getCookies());
        }
    }

    public void waitElementLocated(By locator) {
        waitElementLocated(locator, 5, 2);
    }

    public void waitElementLocated(By locator, long timeOutInSeconds, int retryCount) {
        SystemException lastEx = null;
        int i = 1;

        while (i <= retryCount) {
            try {
                WebDriverWait wait = new WebDriverWait(driver, timeOutInSeconds);
                wait.until(ExpectedConditions.presenceOfElementLocated(locator));
                return;
            } catch (Exception e) {
                log.info("waitElementLocated: {}", e.getMessage());
                if (findElements(locator).any()) {
                    return;
                }
                if (i == retryCount) {
                    lastEx = SystemException.wrap(e);
                }

                ++i;
            }
        }

        throw lastEx;
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
