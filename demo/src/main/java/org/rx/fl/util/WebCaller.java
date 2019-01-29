package org.rx.fl.util;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rx.common.*;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.fl.util.HttpCaller.IE_UserAgent;

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
    private static final String dataPath = App.readSetting("app.chrome.dataPath");
    private static volatile int pathCounter;

    static {
        clearProcesses();

        System.setProperty("webdriver.chrome.driver", App.readSetting("app.chrome.driver"));
        System.setProperty("webdriver.ie.driver", App.readSetting("app.ie.driver"));
        driverPool = new ConcurrentHashMap<>();
        for (DriverType driverType : DriverType.values()) {
            Integer init = App.readSetting(String.format("app.%s.initSize", driverType.name().toLowerCase()));
            if (init != null) {
                init(driverType, init);
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                clearProcesses();
            } catch (Exception ex) {
                log.error("addShutdownHook", ex);
            }
        }));
    }

    private static RemoteWebDriver create(DriverType driverType, boolean fromPool) {
        RemoteWebDriver driver = null;
        PooledItem pooledItem = driverPool.computeIfAbsent(driverType, k -> {
            DriverService driverService;
            switch (driverType) {
                case IE:
                    driverService = null;
//                    driverService = new InternetExplorerDriverService.Builder()
//                            .withSilent(true).build();
                    break;
                default:
                    driverService = new ChromeDriverService.Builder()
                            .usingAnyFreePort()
//                            .withVerbose(true)
                            .withSilent(false).build();
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
//                            .introduceFlakinessByIgnoringSecurityDomains()
                            .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

                    opt.setCapability(CapabilityType.SUPPORTS_APPLICATION_CACHE, true);

                    DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
//                    capabilities.setAcceptInsecureCerts(true);
                    capabilities.setJavascriptEnabled(true);
                    opt.merge(capabilities);
                    //NoSuchWindowException
//                    opt.setCapability(InternetExplorerDriver.INTRODUCE_FLAKINESS_BY_IGNORING_SECURITY_DOMAINS, true);

                    driver = new InternetExplorerDriver(opt);
//                    driver = new InternetExplorerDriver((InternetExplorerDriverService) pooledItem.driverService, opt);
                }
                break;
                default: {
                    ChromeOptions opt = new ChromeOptions();
                    opt.setHeadless(App.readSetting("app.chrome.isBackground"))
                            .setAcceptInsecureCerts(true)
                            .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

                    opt.setCapability(CapabilityType.SUPPORTS_APPLICATION_CACHE, true);

                    Map<String, Object> chromePrefs = new HashMap<>();
                    String downloadPath = App.readSetting("app.chrome.downloadPath");
                    App.createDirectory(downloadPath);
                    chromePrefs.put("download.default_directory", downloadPath);
                    chromePrefs.put("profile.default_content_settings.popups", 0);
                    chromePrefs.put("pdfjs.disabled", true);
                    opt.setExperimentalOption("prefs", chromePrefs);

                    opt.addArguments("user-agent=" + IE_UserAgent);
                    opt.addArguments("no-first-run", "--homepage=chrome://crash", "window-size=1024,800",
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

    @SneakyThrows
    private static void clearProcesses() {
        String[] pNames = {"chromedriver.exe", "IEDriverServer.exe", "iexplore.exe"};
        App.execShell(null, NQuery.of(pNames).select(p -> "taskkill /F /IM " + p).toArray(String.class));
        Thread.sleep(5000);
    }

    @Getter
    private long waitMillis = 500;
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

    public NQuery<WebElement> navigateUrl(String url, String locatorSelector) {
        return navigateUrl(url, locatorSelector, 4, null);
    }

    @SneakyThrows
    public NQuery<WebElement> navigateUrl(String url, String locatorSelector, int retryCount, Predicate<By> onRetry) {
        checkNotClosed();
        require(url);

        if (isShareCookie) {
            WebDriver.Options manage = driver.manage();
            Action action = () -> {
                Set<Cookie> set = HttpCaller.CookieContainer.loadForRequest(url);
                for (Cookie cookie : set) {
                    manage.addCookie(cookie);
                    log.info("{} load cookie: {}={}", HttpUrl.get(url).topPrivateDomain(), cookie.getName(), cookie.getValue());
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
        try {
            driver.get(url);
        } catch (NoSuchWindowException e) {
            log.info("navigateUrl {}", e.getMessage());
            if (driver instanceof InternetExplorerDriver) {
                RemoteWebDriver temp = driver;
                driver = create(DriverType.IE, true);
                driver.get(url);
                temp.quit();
            }
        }

        NQuery<WebElement> elements;
        if (locatorSelector == null) {
            elements = NQuery.of();
        } else {
            elements = waitElementLocated(locatorSelector, retryCount, onRetry);
        }
        if (isShareCookie) {
            syncCookie();
        }
        return elements;
    }

    public void syncCookie() {
        checkNotClosed();

        if (driver instanceof InternetExplorerDriver) {
            String localHost = String.format((String) App.readSetting("app.ie.cookieUrl"),
                    HttpUrl.get(getCurrentUrl()).topPrivateDomain());
            invokeSelf(caller -> {
                String selector = "#rx";
                caller.navigateUrl(localHost, selector);
                String rawCookie = caller.elementAttr(selector, "value");
                log.info("getIECookie: {}", rawCookie);
            });
        } else {
            WebDriver.Options manage = driver.manage();
            HttpCaller.CookieContainer.saveFromResponse(getCurrentUrl(), manage.getCookies());
        }
    }

    public NQuery<WebElement> waitElementLocated(String selector) {
        return waitElementLocated(selector, 4, null);
    }

    public NQuery<WebElement> waitElementLocated(String selector, int retryCount, Predicate<By> onRetry) {
        require(selector);
        By locator = By.cssSelector(selector);

        SystemException lastEx = null;
        WebDriverWait wait = null;
        int i = 1;
        while (i <= retryCount) {
            try {
                NQuery<WebElement> elements = findElements(locator, false);
                if (elements.any()) {
                    log.info("Wait {} located ok", locator);
                    return elements;
                }

                if (wait == null) {
                    wait = new WebDriverWait(driver, 1);
                }
                wait.until(ExpectedConditions.presenceOfElementLocated(locator));
            } catch (Exception e) {
                log.info("waitElementLocated {}", e.getMessage());
                lastEx = SystemException.wrap(e);

                NQuery<WebElement> elements = findElements(locator, false);
                if (elements.any()) {
                    log.info("Wait {} located ok", locator);
                    return elements;
                }

                if (onRetry != null && !onRetry.test(locator)) {
                    break;
                }
                if (i == retryCount) {
                    throw SystemException.wrap(e);
                }
                i++;
            }
        }

        if (lastEx != null) {
            throw lastEx;
        }
        throw new InvalidOperationException("No such elements");
    }

    @SneakyThrows
    public void waitClickComplete(String selector, int reClickCount, Func<Boolean> checkState) {
        require(selector, checkState);
        By clickBy = By.cssSelector(selector);

        int count = 0;
        do {
            if (count == 0) {
                WebElement btn = findElement(clickBy, false);
                if (btn == null) {
                    log.info("btn-{} missing..", clickBy);
                    break;
                }
                btn.click();
                log.info("btn-{} click..", clickBy);
            }
            log.info("wait btn-{} callback..", clickBy);
            Thread.sleep(waitMillis);
            count++;
            if (count >= reClickCount) {
                count = 0;
            }
        }
        while (checkState.invoke());
    }

    @SneakyThrows
    public void wait(int checkCount, Func<Boolean> checkState, boolean sleepFirst) {
        require(checkState);

        int count = 0;
        do {
            if (sleepFirst) {
                Thread.sleep(waitMillis);
            }
            if (checkState.invoke()) {
                break;
            }
            if (!sleepFirst) {
                Thread.sleep(waitMillis);
            }
            count++;
        }
        while (count < checkCount);
    }

    private WebElement findElement(By by, boolean throwOnEmpty) {
        checkNotClosed();
        require(by);

        NQuery<WebElement> elements = findElements(by, throwOnEmpty);
        if (!elements.any()) {
            if (throwOnEmpty) {
                throw new InvalidOperationException("Element %s not found", by);
            }
            return null;
        }
        return elements.first();
    }

    private NQuery<WebElement> findElements(By by, boolean throwOnEmpty) {
        checkNotClosed();
        require(by);

        try {
            return NQuery.of(driver.findElements(by));
        } catch (NoSuchElementException e) {
            if (throwOnEmpty) {
                throw e;
            }
            return NQuery.of();
        }
    }

    public boolean hasElement(String selector) {
        return findElements(By.cssSelector(selector), false).any();
    }

    public String elementText(String selector) {
        return isNull(elementsText(selector).firstOrDefault(), "");
    }

    public NQuery<String> elementsText(String selector) {
        checkNotClosed();
        require(selector);

        return findElements(By.cssSelector(selector), false).select(p -> p.getText());
    }

    public String elementVal(String selector) {
        return isNull(elementsVal(selector).firstOrDefault(), "");
    }

    public NQuery<String> elementsVal(String selector) {
        return elementsAttr(selector, "value");
    }

    public String elementAttr(String selector, String... attrArgs) {
        return isNull(elementsAttr(selector, attrArgs).firstOrDefault(), "");
    }

    public NQuery<String> elementsAttr(String selector, String... attrArgs) {
        checkNotClosed();
        require(selector, attrArgs);
        require(attrArgs, attrArgs.length > 0);

        String attrName = attrArgs[0], attrVal = attrArgs.length > 1 ? attrArgs[1] : null;
        NQuery<WebElement> elements = findElements(By.cssSelector(selector), false);
        if (attrVal != null) {
            for (WebElement elm : elements) {
                executeScript("arguments[0].setAttribute(arguments[1], arguments[2]);", elm, attrName, attrVal);
            }
            return NQuery.of();
        }
        return elements.select(p -> p.getAttribute(attrName));
    }

    public void elementClick(String selector) {
        elementClick(selector, false);
    }

    public void elementClick(String selector, boolean waitElementLocated) {
        checkNotClosed();
        require(selector);

        WebElement element;
        if (waitElementLocated) {
            element = waitElementLocated(selector).first();
        } else {
            element = findElement(By.cssSelector(selector), true);
        }
        try {
            element.click();
        } catch (WebDriverException e) {
            log.info("Script click element {}", selector);
            executeScript(String.format("$('%s').click();", selector));
        }
    }

    public <T> T executeScript(String script, Object... args) {
        checkNotClosed();
        require(script);

        return (T) driver.executeScript(script, args);
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
}
