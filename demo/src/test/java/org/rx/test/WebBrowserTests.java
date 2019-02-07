package org.rx.test;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.rx.common.App;
import org.rx.common.BrowserConfig;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebBrowser;
import org.rx.util.JsonMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class WebBrowserTests {
    @Test
    public void config() {
        BrowserConfig browserConfig = App.readSetting("app.browser", BrowserConfig.class);
        System.out.println(JSON.toJSONString(browserConfig));
    }

    @Test
    public void beanMapper() {
        OrderInfo to = JsonMapper.Default.convertTo(OrderInfo.class, "jdQueryOrderDetail", "{\n" +
                "        \"orderId\": 86053762470,\n" +
                "        \"parentId\": 0,\n" +
                "        \"orderTime\": 0,\n" +
                "        \"orderTimeStr\": \"2019-01-25 16:34:16\",\n" +
                "        \"finishTime\": 0,\n" +
                "        \"finishTimeStr\": \"2019-01-27 09:01:15\",\n" +
                "        \"payMonth\": 0,\n" +
                "        \"payMonthStr\": \"\",\n" +
                "        \"validCode\": 17,\n" +
                "        \"validCodeStr\": \"已完成\",\n" +
                "        \"validCodeUrl\": \"\",\n" +
                "        \"orderEmt\": 0,\n" +
                "        \"orderEmtStr\": \"无线\",\n" +
                "        \"plus\": 1,\n" +
                "        \"plusStr\": \"是\",\n" +
                "        \"isAfterSale\": 0,\n" +
                "        \"orderSkuDetailInfos\": [\n" +
                "          {\n" +
                "            \"skuId\": 32641401757,\n" +
                "            \"skuName\": \"威刚（ADATA）XPG 龙耀D41  RGB幻光渐层内存 DDR4 台式机电脑内存灯条 单条【8G】 2666 频率\",\n" +
                "            \"skuNum\": 1,\n" +
                "            \"skuReturnNum\": 0,\n" +
                "            \"frozenSkuNum\": 0,\n" +
                "            \"price\": 429,\n" +
                "            \"payPrice\": 402,\n" +
                "            \"commissionRate\": 1.8,\n" +
                "            \"subSideRate\": 90,\n" +
                "            \"subsidyRate\": 0,\n" +
                "            \"finalRate\": 90,\n" +
                "            \"estimateCosPrice\": 404,\n" +
                "            \"estimateCommission\": 7.27,\n" +
                "            \"estimateFee\": 6.54,\n" +
                "            \"actualCosPrice\": 402,\n" +
                "            \"actualCommission\": 7.24,\n" +
                "            \"actualFee\": 6.52,\n" +
                "            \"validCode\": 17,\n" +
                "            \"validCodeStr\": \"已完成\",\n" +
                "            \"traceType\": 2,\n" +
                "            \"traceTypeStr\": \"同店\",\n" +
                "            \"spId\": 0,\n" +
                "            \"spIdStr\": \"--\",\n" +
                "            \"siteId\": 0,\n" +
                "            \"unionAlias\": \"\",\n" +
                "            \"pid\": \"\",\n" +
                "            \"orderId\": 86053762470,\n" +
                "            \"plus\": 1,\n" +
                "            \"plusStr\": \"是\",\n" +
                "            \"imgUrl\": \"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\",\n" +
                "            \"orderTimeStr\": \"2019-01-25 16:34:16\",\n" +
                "            \"finishTimeStr\": \"2019-01-27 09:01:15\",\n" +
                "            \"payMonthStr\": \"\",\n" +
                "            \"validCodeUrl\": \"\",\n" +
                "            \"subsidyParty\": \"\",\n" +
                "            \"subsidyPartyStr\": \"-\",\n" +
                "            \"unionTrafficGroup\": 5,\n" +
                "            \"unionTrafficTypeStr\": \"京东\",\n" +
                "            \"unionTag\": \"00000000\",\n" +
                "            \"isPinGouStr\": \"否\",\n" +
                "            \"isJXRedRocket\": \"否\"\n" +
                "          }\n" +
                "        ],\n" +
                "        \"imgUrlS\": [\n" +
                "          \"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\"\n" +
                "        ],\n" +
                "        \"skuLists\": [],\n" +
                "        \"payPriceSum\": 404,\n" +
                "        \"commissionRateOrderStr\": \"1.80\",\n" +
                "        \"finalRateOrderStr\": \"90.00\",\n" +
                "        \"estimateFeeOrder\": 6.54,\n" +
                "        \"actualFeeOrder\": 6.52,\n" +
                "        \"actualCosPriceOrder\": 402,\n" +
                "        \"unionTrafficGroup\": 5,\n" +
                "        \"unionTrafficTypeStr\": \"京东\",\n" +
                "        \"spId\": 0,\n" +
                "        \"spIdStr\": \"--\",\n" +
                "        \"siteId\": 0,\n" +
                "        \"pid\": \"\",\n" +
                "        \"isPinGouStr\": \"否\",\n" +
                "        \"isJXRedRocket\": \"否\"\n" +
                "      }");
        System.out.println(to);
    }

    @SneakyThrows
    @Test
    public void download() {
        String refUrl = "https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5HmjmiY#!/report/detail/taoke";
//        HttpUrl.get(refUrl).queryParameter("")
        String rawCookie = "isg=BCEhEApn2ciykHU8ek0CJg5-OO07zpXAjSi_woP2HSiH6kG8yx6lkE-4StxJOS34; t=70824e08423cb52e5173c58b0dee1a93; cna=6N84E+HCOwcCAXngjNKxcUwW; l=aB7DTtLdyUaWZyQpDMaPsVhISxrxygBPpkTZBMaLzTqGdP8vhtS1fjno-VwkQ_qC5f9L_XtiI; cookie2=1391a802ada07c947d4f6dc4f332bfaa; v=0; _tb_token_=fe5b3865573ee; alimamapwag=TW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV09XNjQ7IFRyaWRlbnQvNy4wOyBydjoxMS4wKSBsaWtlIEdlY2tv; cookie32=da263646f8b7310d20a6241569cb21ca; alimamapw=TgoJAwYAAgoEBmsJUVQABQMFDglSAwAIBVpQBQEKUwMHUVMFCF8EB1NUVQ%3D%3D; cookie31=MzcxNTE0NDcsdzM5NTExNTMyMyxyb2NreXdvbmcuY2huQGdtYWlsLmNvbSxUQg%3D%3D; login=Vq8l%2BKCLz3%2F65A%3D%3D";
        HttpCaller.CookieContainer.saveFromResponse(HttpUrl.get(refUrl), HttpCaller.parseRawCookie(HttpUrl.get(refUrl), rawCookie));
        String url = "https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.10.19ef35d9uFsIOb&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=2019-01-05&endTime=2019-01-11";
        HttpCaller caller = new HttpCaller();
//        caller.setHeaders(HttpCaller.parseOriginalHeader("Accept: text/html, application/xhtml+xml, image/jxr, */*\n" +
////                "Referer: https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5khPITz\n" +
////                "Accept-Language: en-US,en;q=0.8,zh-Hans-CN;q=0.5,zh-Hans;q=0.3\n" +
////                "User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko\n" +
////                "Accept-Encoding: gzip, deflate\n" +
////                "Host: pub.alimama.com\n" +
////                "Connection: Keep-Alive\n" +
//                "Cookie: "+rawCookie));
        caller.getDownload(url, "D:\\a.xls");
//        WebBrowser caller = new WebBrowser();
//        caller.setShareCookie(true);
//        caller.navigateUrl(refUrl);
//
//        Thread.sleep(5000);
//
//        caller.navigateUrl(refUrl+"&t=t");
//        System.in.read();

//        GET https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.10.19ef35d9uFsIOb&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=2019-01-05&endTime=2019-01-11 HTTP/1.1
//        Accept: text/html, application/xhtml+xml, image/jxr, */*
////Referer: https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5khPITz
////Accept-Language: en-US,en;q=0.8,zh-Hans-CN;q=0.5,zh-Hans;q=0.3
////User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko
////Accept-Encoding: gzip, deflate
////Host: pub.alimama.com
////Connection: Keep-Alive
////Cookie: isg=BFpa-sxa0m0LcF7BHai5oxGjowB8i95lYmXUH2TTBu241_oRTBsudSDlo2FuHFb9; t=70824e08423cb52e5173c58b0dee1a93; cna=6N84E+HCOwcCAXngjNKxcUwW; l=aB7DTtLdyUaWZGbmSMaH2VW5FxrxygBPpNPzBMaLzTqGdP8vhtS1fjno-Vwkc_qC5vvy_XtiI; cookie2=10f3b5f03dc43cb743097f883ef452c6; v=0; _tb_token_=75661b586ee93; alimamapwag=TW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV09XNjQ7IFRyaWRlbnQvNy4wOyBydjoxMS4wKSBsaWtlIEdlY2tv; cookie32=da263646f8b7310d20a6241569cb21ca; alimamapw=TgoJAwYAAgoEBmsJUVQABQMFDglSAwAIBVpQBQEKUwMHUVMFCF8EB1NUVQ%3D%3D; cookie31=MzcxNTE0NDcsdzM5NTExNTMyMyxyb2NreXdvbmcuY2huQGdtYWlsLmNvbSxUQg%3D%3D; login=UIHiLt3xD8xYTw%3D%3D; rurl=aHR0cHM6Ly9wdWIuYWxpbWFtYS5jb20v; account-path-guide-s1=true
////


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
        WebBrowser webBrowser = new WebBrowser();
        webBrowser.setShareCookie(true);
        webBrowser.navigateUrl("https://pub.alimama.com");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testWebLogin() {
        System.setProperty("webdriver.chrome.driver", App.readSetting("app.chrome.driver"));
        System.setProperty("webdriver.ie.driver", App.readSetting("app.ie.driver"));
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
    public void testTab() {
        WebBrowser caller = new WebBrowser();
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
