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
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerDriverService;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rx.common.*;
import org.rx.util.function.Action;

import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.common.Contract.eq;
import static org.rx.common.Contract.require;

@Slf4j
public final class WebCaller extends Disposable {
    public enum DriverType {
        Chrome,
        IE
    }

    private static class PooledItem {
        public final DriverService driverService;
        public final ConcurrentLinkedQueue<RemoteWebDriver> drivers;

        public PooledItem(DriverService driverService) {
            this.driverService = driverService;
            drivers = new ConcurrentLinkedQueue<>();
        }
    }

    private static final ConcurrentHashMap<DriverType, PooledItem> driverPool;
    private static final String dataPath = (String) App.readSetting("app.chrome.dataPath");
    private static volatile int pathCounter;

    static {
        System.setProperty("webdriver.chrome.driver", (String) App.readSetting("app.chrome.driver"));
        System.setProperty("webdriver.ie.driver", (String) App.readSetting("app.ie.driver"));
        driverPool = new ConcurrentHashMap<>();
        for (DriverType driverType : DriverType.values()) {
            Integer init = (Integer) App.readSetting(String.format("app.%s.initSize", driverType.name().toLowerCase()));
            if (init != null) {
                init(driverType, init);
            }
        }
    }

    private static RemoteWebDriver create(DriverType driverType, boolean fromPool) {
        RemoteWebDriver driver = null;
        PooledItem pooledItem = driverPool.computeIfAbsent(driverType, k -> {
            DriverService driverService;
            switch (driverType) {
                case IE:
                    driverService = new InternetExplorerDriverService.Builder()
                            .withSilent(true).build();
                    break;
                default:
                    driverService = new ChromeDriverService.Builder()
                            .usingAnyFreePort()
                            .withSilent(true).build();
                    break;
            }
            return new PooledItem(driverService);
        });
        if (fromPool) {
            driver = pooledItem.drivers.poll();
        }
        if (driver == null) {
            log.info("create {} driver...", driverType);
            switch (driverType) {
                case IE: {
                    InternetExplorerOptions opt = new InternetExplorerOptions();
                    opt.withInitialBrowserUrl("about:blank")
                            .ignoreZoomSettings()
                            .introduceFlakinessByIgnoringSecurityDomains()
                            .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

                    DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
//                    capabilities.setAcceptInsecureCerts(true);
                    capabilities.setJavascriptEnabled(true);
                    opt.merge(capabilities);
//                    opt.setCapability(InternetExplorerDriver.INTRODUCE_FLAKINESS_BY_IGNORING_SECURITY_DOMAINS, true);

                    driver = new InternetExplorerDriver((InternetExplorerDriverService) pooledItem.driverService, opt);
                }
                break;
                default: {
                    ChromeOptions opt = new ChromeOptions();
                    opt.setHeadless((boolean) App.readSetting("app.chrome.isBackground"))
                            .setAcceptInsecureCerts(true)
                            .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

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

                    opt.addArguments("user-agent=Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko");
                    opt.addArguments("no-first-run", "homepage=about:blank", "window-size=1024,800",
                            "disable-infobars", "disable-web-security", "ignore-certificate-errors", "allow-running-insecure-content",
                            "disable-accelerated-video", "disable-java", "disable-plugins", "disable-plugins-discovery", "disable-extensions",
                            "disable-desktop-notifications", "disable-speech-input", "disable-translate", "safebrowsing-disable-download-protection", "no-pings",
                            "ash-force-desktop", "disable-background-mode", "no-sandbox", "test-type=webdriver");
//                    opt.addArguments("window-position=", "disable-dev-shm-usage");
                    if (!Strings.isNullOrEmpty(dataPath)) {
                        opt.addArguments("user-data-dir=" + dataPath + pathCounter++, "restore-last-session");
                    }

                    driver = new ChromeDriver((ChromeDriverService) pooledItem.driverService, opt);
                }
                break;
            }
        }
        return driver;
    }

    private static void release(RemoteWebDriver driver) {
        DriverType driverType;
        if (driver.getClass().equals(InternetExplorerDriver.class)) {
            driverType = DriverType.IE;
        } else {
            driverType = DriverType.Chrome;
        }
        PooledItem pooledItem = driverPool.get(driverType);
        if (pooledItem == null) {
            return;
        }
        pooledItem.drivers.add(driver);
    }

    public static void init(DriverType driverType, int count) {
        int left = count - driverPool.size();
        for (int i = 0; i < left; i++) {
            release(create(driverType, false));
        }
    }

    public synchronized static void purgeAll() {
        for (RemoteWebDriver driver : NQuery.of(driverPool.values()).selectMany(p -> p.drivers)) {
            driver.quit();
        }
        driverPool.clear();
    }

    @Getter
    @Setter
    private boolean isShareCookie;
    private RemoteWebDriver driver;
    private Lazy<ReentrantLock> locker;

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
        this(DriverType.Chrome);
    }

    public WebCaller(DriverType driverType) {
        require(driverType);

        driver = create(driverType, true);
        locker = new Lazy<>(ReentrantLock.class);
    }

    @Override
    protected void freeObjects() {
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

        Lock lock = locker.getValue();
        if (!lock.tryLock()) {
            if (skipIfLocked) {
                return;
            }
            lock.lock();
        }
        try {
            consumer.accept(this);
        } finally {
            lock.unlock();
        }
    }

    public <T> T invokeSelf(Function<WebCaller, T> consumer) {
        return invokeSelf(consumer, false);
    }

    public <T> T invokeSelf(Function<WebCaller, T> consumer, boolean skipIfLocked) {
        checkNotClosed();
        require(consumer);

        Lock lock = locker.getValue();
        if (!lock.tryLock()) {
            if (skipIfLocked) {
                return null;
            }
            lock.lock();
        }
        try {
            return consumer.apply(this);
        } finally {
            lock.unlock();
        }
    }

    public void invokeNew(Consumer<WebCaller> consumer) {
        invokeNew(consumer, DriverType.Chrome);
    }

    public void invokeNew(Consumer<WebCaller> consumer, DriverType driverType) {
        checkNotClosed();
        require(consumer, driverType);

        try (WebCaller caller = new WebCaller(driverType)) {
            consumer.accept(caller);
        }
    }

    public <T> T invokeNew(Function<WebCaller, T> consumer) {
        return invokeNew(consumer, DriverType.Chrome);
    }

    public <T> T invokeNew(Function<WebCaller, T> consumer, DriverType driverType) {
        checkNotClosed();
        require(consumer, driverType);

        try (WebCaller caller = new WebCaller(driverType)) {
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
            String host = new URL(url).getHost();
            Action action = () -> {
                Set<Cookie> set = HttpCaller.CookieContainer.loadForRequest(url);
                for (Cookie p : set) {
                    log.debug("{} load cookie: " + p.getDomain() + " / " + p.getName() + "=" + p.getValue(), host);
                    manage.addCookie(p);
                }
            };
            try {
                action.invoke();
            } catch (UnableToSetCookieException e) {
                log.debug(e.getMessage());
//                driver.get(url);
//                action.invoke();
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
        waitElementLocated(locator, 5, 2, null);
    }

    public void waitElementLocated(By locator, long timeOutInSeconds, int retryCount, Predicate<By> onRetry) {
        require(locator);

        int i = 1;
        while (i <= retryCount) {
            try {
                if (findElements(locator).any()) {
                    return;
                }

                WebDriverWait wait = new WebDriverWait(driver, timeOutInSeconds);
                wait.until(ExpectedConditions.presenceOfElementLocated(locator));
                break;
            } catch (Exception e) {
                log.info("waitElementLocated {}", e.getMessage());
                if (findElements(locator).any()) {
                    break;
                }

                if (onRetry != null) {
                    if (!onRetry.test(locator)) {
                        break;
                    }
                }
                if (i == retryCount) {
                    throw SystemException.wrap(e);
                }
                i++;
            }
        }
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
