package org.rx.fl.service.media;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;
import org.rx.socks.http.HttpClient;
import org.rx.util.Helper;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.rx.common.Contract.toJsonString;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TbMedia implements Media {
    private static final String[] keepLoginUrl = {"https://pub.alimama.com/myunion.htm?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/detail/taoke?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/zone/zone_self?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/manage/overview/index.htm?spm=a219t.7900221/1.1998910419.dbb742793.6f2075a54ffHxF",
            "https://pub.alimama.com/manage/selection/list.htm?spm=a219t.7900221/1.1998910419.d3d9c63c9.6f2075a54ffHxF"};
    private static final Cache<String, Object> cache = CacheBuilder.newBuilder()
            .expireAfterAccess(App.readSetting("app.media.tbCacheSeconds", long.class), TimeUnit.SECONDS).build();

    public static void clearIE() {
        String[] pNames = {"IEDriverServer.exe", "iexplore.exe"};
        try {
            for (String pName : pNames) {
                Process process = Runtime.getRuntime().exec("taskkill /F /IM " + pName);
                process.waitFor();
            }
            Thread.sleep(2000);
        } catch (Exception e) {
            log.info("clearProcesses", e);
        }
    }

    @Getter
    private volatile boolean isLogin;
    @Getter
    @Setter
    private String downloadFileDateFormat;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Taobao;
    }

    public TbMedia() {
        downloadFileDateFormat = "yyyy-MM-dd-HH";
        caller = new WebCaller(WebCaller.DriverType.IE);
//        caller.setShareCookie(true);
        long period = App.readSetting("app.media.tbKeepPeriod", long.class);
        TaskFactory.schedule(() -> keepLogin(), 2 * 1000, period * 1000, "TbMedia");
    }

    @SneakyThrows
    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        String fp = "yyyy-MM-dd";
        String url = String.format("https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.54.353135d9SjsRTc&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=%s&endTime=%s", start.toString(fp), end.toString(fp));
        log.info("findOrders\n{}", url);

        String downloadPath = App.readSetting("app.chrome.downloadPath");
        App.createDirectory(downloadPath);
        String filePath = downloadPath + File.separator + "TbMedia-" + DateTime.now().toString(downloadFileDateFormat) + ".xls";
        HttpCaller caller = new HttpCaller();
//        String rawCookie = HttpCaller.toRawCookie(HttpCaller.CookieContainer.loadForRequest(HttpUrl.get(url)));
//        log.info("findOrders load cookie: {}", rawCookie);
//        caller.setHeaders(HttpCaller.parseOriginalHeader("Accept: text/html, application/xhtml+xml, */*\n" +
//                "Referer: https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5LWuydX\n" +
//                "Accept-Language: zh-CN\n" +
//                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko\n" +
//                "Accept-Encoding: gzip, deflate\n" +
//                "Connection: Keep-Alive\n" +
//                "Cookie: " + rawCookie));
        caller.getDownload(url, filePath);
        List<OrderInfo> orders = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(caller.getDownload(url, filePath))) {
            Map<String, List<Object[]>> excel = Helper.readExcel(in, false);
            List<Object[]> list = excel.get("Page1");
            if (CollectionUtils.isEmpty(list)) {
                return Collections.emptyList();
            }

            final String mapStr = "orderNo#订单编号,goodsId#商品ID,goodsName#商品信息,unitPrice#商品单价,quantity#商品数,sellerName#所属店铺,payAmount#付款金额,rebateAmount#预估收入,status#订单状态,createTime#创建时间";
            Object[] cols = list.get(0);
            NQuery<Tuple<String, Integer>> tuples = NQuery.of(mapStr.split(","))
                    .select(p -> {
                        String[] pair = p.split("#");
                        return Tuple.of(pair[0], findIndex(cols, pair[1]));
                    });
            for (int i = 1; i < list.size(); i++) {
                Object[] vals = list.get(i);
                JSONObject json = new JSONObject();
                json.putAll(tuples.toMap(p -> p.left, p -> vals[p.right]));
                OrderInfo order = json.toJavaObject(OrderInfo.class);
                order.setMediaType(this.getType());
                orders.add(order);
            }
        }
        return orders;
    }

    private int findIndex(Object[] array, String name) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(name)) {
                return i;
            }
        }
        throw new InvalidOperationException(String.format("%s index not found", name));
    }

    @SneakyThrows
    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s&_t=%s", URLEncoder.encode(goodsInfo.getName(), "utf-8"), System.currentTimeMillis());
        return caller.invokeSelf(caller -> {
            log.info("findAdv step1 {}", url);
            By waitBy = By.cssSelector(".box-btn-left");
            caller.navigateUrl(url, waitBy);
//            List<WebElement> eBtns = caller.findElements(first).toList();
            List<WebElement> eSellers = caller.findElements(By.cssSelector("a[vclick-ignore]")).skip(1).toList();
            List<WebElement> eMoneys = caller.findElements(By.cssSelector(".number-16")).toList();
            log.info("findAdv step2 sellerEles: {}\tmoneyEles: {}", eSellers.size(), eMoneys.size());
            for (int i = 0; i < eSellers.size(); i++) {
                WebElement eSeller = eSellers.get(i);
                String sellerName = goodsInfo.getSellerName().trim();
                log.info("findAdv step3 {} == {}", sellerName, eSeller.getText());
                if (!sellerName.equals(eSeller.getText().trim())) {
                    continue;
                }

                int offset = i * 3;
                goodsInfo.setPrice(eMoneys.get(offset).getText().trim());
                goodsInfo.setRebateRatio(eMoneys.get(offset + 1).getText().trim());
                goodsInfo.setRebateAmount(eMoneys.get(offset + 2).getText().trim());
//                try {
//                    Thread.sleep(sleepMillis);  //睡不睡都会step4-1 click unknown error
//                    eBtns.get(i).click();
//                } catch (WebDriverException e) {
//                    log.info("findAdv step4-1 click {}", e.getMessage());
                caller.executeScript("$('.box-btn-left:eq(" + i + ")').click();");
//                }
                log.info("findAdv step4-1 ok");

                try {
                    By btn42By = By.cssSelector("button[mx-click=submit]");
                    WebElement btn42 = caller.findElements(btn42By, btn42By).first();
                    try {
                        btn42.click();
                    } catch (WebDriverException e) {
                        log.info("findAdv step4-2 click %s", e.getMessage());
                        caller.executeScript("$('button[mx-click=submit]').click();");
                    }
                    log.info("findAdv step4-2 ok");

                    waitBy = By.cssSelector("#clipboard-target");
                    caller.waitElementLocated(waitBy, 1, 4, p -> {
                        WebElement code = caller.findElement(By.id("clipboard-target"), false);
                        if (code != null) {
                            log.info("code located ok");
                            return false;
                        }
                        WebElement $btn42 = caller.findElement(btn42By, false);
                        if ($btn42 == null) {
                            log.info("btn42 reclick fail");
                            return false;
                        }
                        $btn42.click();
                        return true;
                    });
                    goodsInfo.setCouponAmount("0");
                    Future<String> future = null;
                    WebElement code2 = caller.findElement(By.cssSelector("#clipboard-target-2"), false);
                    if (code2 != null) {
                        String couponUrl = code2.getAttribute("value");
                        if (Strings.isNullOrEmpty(couponUrl) || !couponUrl.startsWith("http")) {
                            log.info("findAdv step4-2-2 couponUrl fail and retry");
                            couponUrl = caller.executeScript("return $('#clipboard-target-2').val();");
                        }
                        if (Strings.isNullOrEmpty(couponUrl) || !couponUrl.startsWith("http")) {
                            log.info("findAdv step4-2-2 couponUrl is null -> {}", toJsonString(goodsInfo));
                            return "0";
                        }
                        String $couponUrl = couponUrl;
                        future = TaskFactory.run(() -> {
                            log.info("findAdv step4-2-2 couponUrl {}", $couponUrl);
                            return findCouponAmount($couponUrl);
                        });
                    }

//                    waiter = By.cssSelector("li[mx-click='tab(4)']");
//                    WebElement btn43 = caller.findElements(waiter, waiter).first();
//                    Thread.sleep(sleepMillis);
//                    btn43.click();
                    waitBy = By.id("magix_vf_code");
                    caller.waitElementLocated(waitBy);
                    caller.executeScript("$('.tab-nav li:eq(3)').click();return true;");
                    log.info("findAdv step4-3 ok");

                    WebElement codeX = caller.findElement(By.cssSelector("#clipboard-target,#clipboard-target-2"));
                    String code = codeX.getAttribute("value");

                    if (future != null) {
                        try {
                            goodsInfo.setCouponAmount(future.get());
                        } catch (Exception e) {
                            log.info("get coupon amount result fail -> {}", e.getMessage());
                        }
                    }
                    log.info("Goods {} -> {}", toJsonString(goodsInfo), code);
                    return code;
                } catch (Exception e) {
                    throw new InvalidOperationException(e);
                }
            }
            log.info("Goods {} not found", goodsInfo.getName());
            return null;
        });
    }

    @SneakyThrows
    @Override
    public String findCouponAmount(String url) {
        return (String) cache.get(url, () -> caller.invokeNew(caller -> {
            By first = By.cssSelector(".coupons-price");
            caller.navigateUrl(url, first);
            return caller.findElement(first).getText().trim();
        }));
    }

    @SneakyThrows
    @Override
    public GoodsInfo findGoods(String url) {
        return (GoodsInfo) cache.get(url, () -> caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                By hybridSelector = By.cssSelector(".tb-main-title,input[name=title]");
                caller.navigateUrl(url, hybridSelector);
                WebElement hybridElement = caller.findElement(hybridSelector);
                if (caller.getCurrentUrl().contains(".taobao.com/")) {
                    goodsInfo.setName(hybridElement.getText().trim());
                    goodsInfo.setSellerName(caller.findElement(By.cssSelector(".shop-name-link,.tb-shop-name")).getText().trim());
                } else {
                    goodsInfo.setName(hybridElement.getAttribute("value"));
                    goodsInfo.setSellerId(caller.getAttributeValues(By.name("seller_id"), "value").firstOrDefault().trim());
                    goodsInfo.setSellerName(caller.getAttributeValues(By.name("seller_nickname"), "value").firstOrDefault().trim());
                }
                goodsInfo.setImageUrl(caller.findElement(By.cssSelector("#J_ImgBooth")).getAttribute("src"));
                goodsInfo.setId(HttpClient.parseQueryString(new URL(caller.getCurrentUrl()).getQuery()).get("id"));
                log.info("FindGoods {}\n -> {} -> {}", url, caller.getCurrentUrl(), toJsonString(goodsInfo));
                return goodsInfo;
            } catch (Exception e) {
                log.error("findGoods", e);
                return null;
            }
        }));
    }

    @Override
    public String findLink(String content) {
        int s = content.indexOf("http"), e;
        if (s == -1) {
            log.info("Start flag not found {}", content);
            return null;
        }
        e = content.indexOf(" ", s);
        if (e == -1) {
            log.info("End flag not found {}", content);
            return null;
        }
        return content.substring(s, e);
    }

    @SneakyThrows
    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            try {
                String url = "https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false";
                By locator = By.id("J_SubmitQuick");
                caller.navigateUrl(url, locator);
                int count = 0;
                do {
                    if (count == 0) {
                        caller.findElement(locator).click();
                        log.info("login btn click...");
                    }
                    log.info("wait login callback...");
                    Thread.sleep(1000);
                    count++;
                    if (count >= 5) {
                        count = 0;
                    }
                }
                while (caller.getCurrentUrl().startsWith("https://login.taobao.com"));

//                caller.navigateUrl("https://pub.alimama.com/myunion.htm");
//                String url;
//                while ((url = caller.getCurrentUrl()).startsWith("https://www.alimama.com/member/login.htm")) {
//                    log.info("please login {}", url);
//                    isLogin = false;
//                    Thread.sleep(1000);
//                }
//                if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com/myunion.htm")) {
//                    login();
//                }

                log.info("login ok...");
//                caller.syncCookie();
                getIECookie();
                isLogin = true;
            } catch (Exception e) {
                throw new InvalidOperationException(e);
            }
        });
    }

    /**
     * 需要配置hosts
     */
    private void getIECookie() {
        String localHost = "http://pubrx.alimama.com:8080/media/coupon.html?rx=1";
        caller.invokeSelf(caller -> {
            By rx = By.id("rx");
            caller.navigateUrl(localHost, rx);
            String rawCookie = caller.findElement(rx).getAttribute("value");
            log.info("getIECookie: {}", rawCookie);
//            HttpCaller.saveRawCookies(localHost, rawCookie);
        }, true);
    }

    @SneakyThrows
    private void keepLogin() {
        caller.invokeSelf(caller -> {
            String noCache = String.format("&_t=%s", System.currentTimeMillis());
            int i = ThreadLocalRandom.current().nextInt(0, keepLoginUrl.length);
            caller.navigateUrl(keepLoginUrl[i] + noCache);
            if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com")) {
                log.info("login expired...");
                isLogin = false;
                login();
            } else {
                log.info("login ok...");
                isLogin = true;
            }
        }, true);
    }
}
