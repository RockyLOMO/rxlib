package org.rx.test.fl;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.Test;
import org.openqa.selenium.By;
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
import org.rx.socks.Sockets;
import org.rx.socks.http.HttpClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class WebCallerTests {
    @SneakyThrows
    @Test
    public void download() {

        String url = "https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.54.353135d9SjsRTc&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=2019-01-05&endTime=2019-01-11";
        HttpCaller caller = new HttpCaller();
        caller.setHeaders(HttpCaller.parseOriginalHeader("Accept: text/html, application/xhtml+xml, */*\n" +
                "Referer: https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5LWuydX\n" +
                "Accept-Language: zh-CN\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko\n" +
                "Accept-Encoding: gzip, deflate\n" +
                "Host: pub.alimama.com\n" +
                "DNT: 1\n" +
                "Connection: Keep-Alive\n" +
                "Cookie: t=03a265125320ea172f4360b1d940c0f8; cna=3XG8FCHE4k4CAbSpfvqdxFU0; isg=BCMjBNdqezKLeDcLjOsn_McXumfNGLdad9L-61WAvwL5lEG23ejVq8DGiuaaLw9S; l=aB8LrJg1yYwDOsC62Ma_INaulxrxygBPp3C0BMwkuTqGdP89OCB8yotb-_sIMJNFprPa_e2p52f2h; cookie2=1ab3e590958433bd7649251bc0bb9140; v=0; _tb_token_=f73e33eb3e5e7; alimamapwag=TW96aWxsYS81LjAgKFdpbmRvd3MgTlQgNi4xOyBXT1c2NDsgVHJpZGVudC83LjA7IHJ2OjExLjApIGxpa2UgR2Vja28%3D; cookie32=da263646f8b7310d20a6241569cb21ca; alimamapw=R1EMUAFXAABTVThSBlZWVQUCB1JXUAdfB1AHVlJRBAFRAVUCAQQBVFQDVw%3D%3D; cookie31=MzcxNTE0NDcsdzM5NTExNTMyMyxyb2NreXdvbmcuY2huQGdtYWlsLmNvbSxUQg%3D%3D; login=URm48syIIVrSKA%3D%3D; rurl=aHR0cHM6Ly9wdWIuYWxpbWFtYS5jb20v; 37151447_yxjh-filter-1=true; account-path-guide-s1=true; pub-message-center=1"));
        caller.getDownload(url, "D:\\a.xls");

//        String p1 = "https://pub.alimama.com/myunion.htm";
//        String p2 = "https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.54.60a135d9iv17LD&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=2019-01-10&endTime=2019-01-11";
//        String rawCookie = "t=03a265125320ea172f4360b1d940c0f8; cna=3XG8FCHE4k4CAbSpfvqdxFU0; isg=BGpqyp9dwh0nbU787cgO41ZEs9AM2-41ZiFnvPQjFr1IJwrh3Gs-RbCFsdE7zGbN; l=aB8LrJg1yYwDO4bobMa4eVFOrxrxygBPpiMdBMakmTqGdP89OCB8yjno-VwkQ_qC5v9L_XtiI; v=0; _tb_token_=5f363ee1e83bf; cookie32=da263646f8b7310d20a6241569cb21ca; cookie31=MzcxNTE0NDcsdzM5NTExNTMyMyxyb2NreXdvbmcuY2huQGdtYWlsLmNvbSxUQg%3D%3D; login=WqG3DMC9VAQiUQ%3D%3D; 37151447_yxjh-filter-1=true; account-path-guide-s1=true; apush1c7c8d013ee36f5ec9d668a4de851bbd=%7B%22ts%22%3A1547188130949%2C%22parentId%22%3A1547188130938%7D";
//        HttpCaller.CookieContainer.saveFromResponse(HttpUrl.get(p1), HttpCaller.parseRawCookie(p1, rawCookie));
//        List<Cookie> set2 = HttpCaller.CookieContainer.loadForRequest(HttpUrl.get(p2));
//        System.out.println(set2.size());
//        System.in.read();
        //HttpCaller caller = new HttpCaller();
        //caller.getDownload("http://open-prd.oss-cn-hzfinance.aliyuncs.com/dm-instrument/images/5lruwhrtllcgp7ivb4dpl8qwdixxnkab7ymspzff.png", "D:\\1.jpg");
    }

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
        InternetExplorerOptions opt = new InternetExplorerOptions();
        opt.withInitialBrowserUrl("about:blank");
        InternetExplorerDriver driver = new InternetExplorerDriver(opt);
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
