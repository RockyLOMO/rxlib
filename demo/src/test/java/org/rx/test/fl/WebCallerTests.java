package org.rx.test.fl;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerDriverService;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class WebCallerTests {
    @SneakyThrows
    @Test
    public void testLogin() {
        Process exec = Runtime.getRuntime().exec("D:\\Downloads\\login-1\\login\\al.exe", null, new File("D:\\Downloads\\login-1\\login"));
        exec.waitFor();
        String cookie = String.join("", Files.readAllLines(Paths.get("D:\\Downloads\\login-1\\login\\cookie.txt")));
        System.out.println(cookie);
        HttpCaller.CookieContainer.saveFromResponse(HttpUrl.get("http://pub.alimama.com"), NQuery.of(cookie.split("; ")).select(p -> {
            String[] x = p.split("=");
            return new Cookie.Builder().name(x[0]).value(x[1]).domain("alimama.com").build();
        }).toList());
        WebCaller webCaller = new WebCaller();
        webCaller.setShareCookie(true);
        webCaller.navigateUrl("https://pub.alimama.com");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testWebLogin() {
        System.setProperty("webdriver.chrome.driver", (String) App.readSetting("app.chrome.driver"));
        System.setProperty("webdriver.ie.driver", (String) App.readSetting("app.ie.driver"));
        String url = "https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false";
//        DesiredCapabilities ieCaps = DesiredCapabilities.internetExplorer();
//        ieCaps.setCapability(InternetExplorerDriver.INITIAL_BROWSER_URL, "http://www.baidu.com/");
        InternetExplorerDriverService service = new InternetExplorerDriverService.Builder()
//                .withExtractPath(new File("D:\\a"))
                .build();
        InternetExplorerOptions opt = new InternetExplorerOptions();
        opt.withInitialBrowserUrl("about:blank");
        InternetExplorerDriver driver = new InternetExplorerDriver(service, opt);
        driver.get(url);
        Thread.sleep(3000);
        By locator = By.id("J_SubmitQuick");
        while (!driver.getCurrentUrl().contains("alimama.com")) {
            driver.findElement(locator).click();
            System.out.println("click...");
            Thread.sleep(1000);
        }

        System.out.println("url: " + driver.getCurrentUrl());
        for (org.openqa.selenium.Cookie cookie : driver.manage().getCookies()) {
            System.out.println(cookie.getName());
        }
//      driver.getCurrentUrl();
// driver.manage().getCookies();
//        ChromeDriver chromeDriver = new ChromeDriver();
//        chromeDriver.get(driver.getCurrentUrl());
//        System.out.println(chromeDriver.getCurrentUrl());
//        for (org.openqa.selenium.Cookie cookie : driver.manage().getCookies()) {
//            chromeDriver.manage().addCookie(cookie);
//            System.out.println("load " + cookie.getName() + "=" + cookie.getValue());
//        }
//        chromeDriver.get(driver.getCurrentUrl());

//        WebDriverWait wait = new WebDriverWait(ie, 15);
//        wait.until(ExpectedConditions.presenceOfElementLocated(locator));

//        ie.executeScript("$('#J_SubmitQuick').click();");
        Thread.sleep(3000);
//        HttpCaller.CookieContainer.saveFromResponse(ie.getCurrentUrl(), ie.manage().getCookies());
//        int num = 0;
//        while (true)
//        {
//            HtmlElement elementById = wb.Document.GetElementById("ra-" + num.ToString());
//            if (elementById == null)
//            {
//                break;
//            }
//            string attribute = elementById.GetAttribute("value");
//            Console.WriteLine(attribute);
//            if (attribute == "cntaobao" + user)
//            {
//                elementById.InvokeMember("Click");
//                elementById = wb.Document.GetElementById("J_SubmitQuick");
//                if (elementById != null)
//                {
//                    elementById.InvokeMember("Click");
//                }
//                return true;
//            }
//            num++;
//        }
//        return false;
//        WebCaller webCaller = new WebCaller();
//        webCaller.navigateUrl("https://pub.alimama.com");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testMedia() {
        Media media = new TbMedia();
//        String url;
//        GoodsInfo goods;
//
//        url = media.findLink("【Apple/苹果 iPhone 8 Plus苹果8代5.5寸分期8p正品手机 苹果8plus】https://m.tb.cn/h.3JWcCjA 点击链接，再选择浏览器咑閞；或復·制这段描述￥x4aVbLnW5Cz￥后到淘♂寳♀");
//        goods = media.findGoods(url);
//
//        media.login();
//        String code = media.findAdv(goods);
//
//        Function<String, Double> convert = p -> {
//            if (Strings.isNullOrEmpty(p)) {
//                return 0d;
//            }
//            return App.changeType(p.replace("￥", ""), double.class);
//        };
//        Double payAmount = convert.apply(goods.getPrice())
//                - convert.apply(goods.getRebateAmount())
//                - convert.apply(goods.getCouponAmount());
//        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
//                goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
//        System.out.println(content);

        media.login();
        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-30), DateTime.now());
        System.out.println(JSON.toJSONString(orders));

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testTab() {
        WebCaller caller = new WebCaller();
        String currentHandle = caller.getCurrentHandle();
        System.out.println(currentHandle);

        String handle = caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.switchTab(handle);
        System.out.println("switch");
        Thread.sleep(2000);

        caller.closeTab(handle);
        System.out.println("close");
    }
}
