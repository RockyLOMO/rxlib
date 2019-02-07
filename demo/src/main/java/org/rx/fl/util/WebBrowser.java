package org.rx.fl.util;

import com.alibaba.fastjson.JSON;
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.fl.util.HttpCaller.IE_UserAgent;

@Slf4j
public final class WebBrowser extends Disposable {
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

    public static final String BodySelector = "body";
    public static final BrowserConfig BrowserConfig;
    public static final Rectangle WindowRectangle;
    private static volatile int chromeCounter;
    private static final ConcurrentHashMap<DriverType, PooledItem> driverPool;

    static {
        clearProcesses();

        BrowserConfig = App.readSetting("app.browser", BrowserConfig.class);
        log.info("WebBrowser load config {}", JSON.toJSONString(BrowserConfig));
        if (!Strings.isNullOrEmpty(BrowserConfig.getWindowRectangle())) {
            List<Integer> list = NQuery.of(BrowserConfig.getWindowRectangle().split(",")).select(p -> Integer.valueOf(p)).toList();
            WindowRectangle = new Rectangle(list.get(0), list.get(1), list.get(2), list.get(3));
        } else {
            WindowRectangle = null;
        }
        System.setProperty("webdriver.chrome.driver", BrowserConfig.getChrome().getDriver());
        System.setProperty("webdriver.ie.driver", BrowserConfig.getIe().getDriver());
        driverPool = new ConcurrentHashMap<>();
        for (DriverType driverType : DriverType.values()) {
            int size;
            switch (driverType) {
                case IE:
                    size = BrowserConfig.getIe().getInitSize();
                    break;
                default:
                    size = BrowserConfig.getChrome().getInitSize();
                    break;
            }
            init(driverType, size);
        }
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
                    BrowserConfig.ChromeConfig chrome = BrowserConfig.getChrome();
                    ChromeOptions opt = new ChromeOptions();
                    opt.setHeadless(chrome.isBackground())
                            .setAcceptInsecureCerts(true)
                            .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);

                    opt.setCapability(CapabilityType.SUPPORTS_APPLICATION_CACHE, true);

                    Map<String, Object> chromePrefs = new HashMap<>();
                    App.createDirectory(chrome.getDownloadPath());
                    chromePrefs.put("download.default_directory", chrome.getDownloadPath());
                    chromePrefs.put("profile.default_content_settings.popups", 0);
                    chromePrefs.put("pdfjs.disabled", true);
                    opt.setExperimentalOption("prefs", chromePrefs);

                    opt.addArguments("user-agent=" + IE_UserAgent);
                    opt.addArguments("no-first-run", "--homepage=chrome://crash",
                            "disable-infobars", "disable-web-security", "ignore-certificate-errors", "allow-running-insecure-content",
                            "disable-accelerated-video", "disable-java", "disable-plugins", "disable-plugins-discovery", "disable-extensions",
                            "disable-desktop-notifications", "disable-speech-input", "disable-translate", "safebrowsing-disable-download-protection", "no-pings",
                            "ash-force-desktop", "disable-background-mode", "no-sandbox");
//                    opt.addArguments("disable-dev-shm-usage");
                    if (!Strings.isNullOrEmpty(chrome.getDataPath())) {
                        opt.addArguments("user-data-dir=" + chrome.getDataPath() + chromeCounter++, "restore-last-session");
                    }

                    driver = new ChromeDriver((ChromeDriverService) pooledItem.driverService, opt);
                }
                break;
            }
            if (WindowRectangle != null) {
                WebDriver.Window window = driver.manage().window();
                window.setPosition(WindowRectangle.getPoint());
                window.setSize(WindowRectangle.getDimension());
            }
        }
        return driver;
    }

    private static void release(RemoteWebDriver driver) {
        DriverType driverType;
        if (driver instanceof InternetExplorerDriver) {
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
        Thread.sleep(4000);
    }

    @Getter
    @Setter
    private boolean isShareCookie;
    @Getter
    private long waitMillis;
    private RemoteWebDriver driver;
    private Lazy<ReentrantLock> locker;

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public String getCurrentHandle() {
        return driver.getWindowHandle();
    }

    public Rectangle getWindowRectangle() {
        checkNotClosed();

        WebDriver.Window window = driver.manage().window();
        Point point = window.getPosition();
        Dimension size = window.getSize();
        return new Rectangle(point.x, point.y, size.width, size.height);
    }

    public void setWindowRectangle(Rectangle rectangle) {
        checkNotClosed();
        require(rectangle);

        WebDriver.Window window = driver.manage().window();
        window.setPosition(rectangle.getPoint());
        window.setSize(rectangle.getDimension());
    }

    public WebBrowser() {
        this(DriverType.Chrome);
    }

    public WebBrowser(DriverType driverType) {
        require(driverType);

        waitMillis = 400;
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

    public void maximize() {
        checkNotClosed();

        WebDriver.Window window = driver.manage().window();
        window.maximize();
    }

    public void switchToDefault() {
        checkNotClosed();
        driver.switchTo().defaultContent();
    }

    public void switchToFrame(String selector) {
        checkNotClosed();
        require(selector);
        WebElement element = findElement(By.cssSelector(selector), true);

        driver.switchTo().frame(element);
    }

    public void invokeSelf(Consumer<WebBrowser> consumer) {
        invokeSelf(consumer, false);
    }

    public void invokeSelf(Consumer<WebBrowser> consumer, boolean skipIfLocked) {
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

    public <T> T invokeSelf(Function<WebBrowser, T> consumer) {
        return invokeSelf(consumer, false);
    }

    public <T> T invokeSelf(Function<WebBrowser, T> consumer, boolean skipIfLocked) {
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

    public void invokeNew(Consumer<WebBrowser> consumer) {
        invokeNew(consumer, DriverType.Chrome);
    }

    public void invokeNew(Consumer<WebBrowser> consumer, DriverType driverType) {
        checkNotClosed();
        require(consumer, driverType);

        try (WebBrowser caller = new WebBrowser(driverType)) {
            consumer.accept(caller);
        }
    }

    public <T> T invokeNew(Function<WebBrowser, T> consumer) {
        return invokeNew(consumer, DriverType.Chrome);
    }

    public <T> T invokeNew(Function<WebBrowser, T> consumer, DriverType driverType) {
        checkNotClosed();
        require(consumer, driverType);

        try (WebBrowser caller = new WebBrowser(driverType)) {
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
    public NQuery<WebElement> navigateUrl(String url, String locatorSelector, int timeoutSeconds, Predicate<Integer> checkComplete) {
        checkNotClosed();
        require(url);

        boolean setCookie = false;
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
                setCookie = true;
            } catch (UnableToSetCookieException e) {
                log.debug(e.getMessage());
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
                log.info("exchange driver for {}", url);
            }
        } catch (WebDriverException e) {
            if (driver instanceof ChromeDriver && e.getMessage().contains("session deleted because of page crash")) {
                driver = create(DriverType.Chrome, true);
                driver.get(url);
                log.info("exchange driver for {}", url);
            }
            throw e;
        }

        NQuery<WebElement> elements;
        if (locatorSelector == null) {
            elements = NQuery.of();
        } else {
            elements = waitElementLocated(locatorSelector, timeoutSeconds, checkComplete);
        }
        if (setCookie) {
            syncCookie();
        }
        return elements;
    }

    public void syncCookie() {
        checkNotClosed();

        if (driver instanceof InternetExplorerDriver) {
            String localHost = String.format(BrowserConfig.getIe().getCookieUrl(), HttpUrl.get(getCurrentUrl()).topPrivateDomain());
            invokeSelf(caller -> {
                String selector = "#cookie";
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

    public NQuery<WebElement> waitElementLocated(String selector, int timeoutSeconds, Predicate<Integer> checkComplete) {
        require(selector);
        By locator = By.cssSelector(selector);

        SystemException lastEx = null;
        WebDriverWait wait = null;
        int count = 0, loopCount = Math.round(timeoutSeconds * 1000f / waitMillis);
        do {
            NQuery<WebElement> elements = findElements(locator, false);
            if (elements.any()) {
                log.info("Wait {} located ok", locator);
                return elements;
            }

            try {
                if (wait == null) {
                    wait = new WebDriverWait(driver, 1);
                    wait.withTimeout(Duration.ofMillis(waitMillis));
                }
                wait.until(ExpectedConditions.presenceOfElementLocated(locator));
            } catch (Exception e) {
                log.info("waitElementLocated {}", e.getMessage());
                lastEx = SystemException.wrap(e);
            }

            elements = findElements(locator, false);
            if (elements.any()) {
                log.info("Wait {} located ok", locator);
                return elements;
            }
            if (checkComplete != null && checkComplete.test(count)) {
                return elements;
            }
        } while (count++ < loopCount);
        throw lastEx != null ? lastEx : new InvalidOperationException("No such elements");
    }

    @SneakyThrows
    public void waitClickComplete(int timeoutSeconds, Predicate<Integer> checkComplete, String btnSelector, int reClickEachSeconds, boolean skipFirstClick) {
        require(checkComplete, btnSelector);
        require(reClickEachSeconds, reClickEachSeconds > 0);

        int reClickCount = Math.round(reClickEachSeconds * 1000f / waitMillis);
        waitComplete(timeoutSeconds, count -> {
            if (!(count == 0 && skipFirstClick) && count % reClickCount == 0) {
                try {
                    elementClick(btnSelector, true);
                    log.info("Element {} click..", btnSelector);
                } catch (InvalidOperationException e) {
                    log.info(e.getMessage());
                    return true;
                }
            }
            log.info("Wait element {} click callback..", btnSelector);
            return checkComplete.test(count);
        }, true);
    }

    @SneakyThrows
    public void waitComplete(int timeoutSeconds, Predicate<Integer> checkComplete, boolean throwOnFail) {
        require(checkComplete);

        int count = 0, loopCount = Math.round(timeoutSeconds * 1000f / waitMillis);
        do {
            if (checkComplete.test(count)) {
                return;
            }
            Thread.sleep(waitMillis);
        }
        while (count++ < loopCount);
        if (throwOnFail) {
            throw new TimeoutException("Wait complete fail");
        }
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
            element = waitElementLocated(selector).firstOrDefault();
        } else {
            element = findElement(By.cssSelector(selector), false);
        }
        if (element == null) {
            throw new InvalidOperationException("Element {} missing..");
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
