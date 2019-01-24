package org.rx.fl.service.media;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.openqa.selenium.WebElement;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.common.*;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;
import org.rx.util.Helper;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.toJsonString;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TbMedia implements Media {
    private static final String loginUrl = "https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false";
    private static final String[] keepLoginUrl = {"https://pub.alimama.com/myunion.htm?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/detail/taoke?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/zone/zone_self?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/manage/overview/index.htm?spm=a219t.7900221/1.1998910419.dbb742793.6f2075a54ffHxF",
            "https://pub.alimama.com/manage/selection/list.htm?spm=a219t.7900221/1.1998910419.d3d9c63c9.6f2075a54ffHxF"};

    @Getter
    private volatile boolean isLogin;
    @Getter
    @Setter
    private volatile boolean shareCookie;
    @Getter
    @Setter
    private volatile String downloadFileDateFormat;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Taobao;
    }

    public TbMedia() {
        shareCookie = true;
        downloadFileDateFormat = "yyyy-MM-dd-HH";
        caller = new WebCaller(WebCaller.DriverType.IE);
        long period = App.readSetting("app.media.tbKeepPeriod", long.class);
        TaskFactory.schedule(() -> keepLogin(true), 2 * 1000, period * 1000, "TbMedia");
    }

    @SneakyThrows
    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        login();
        if (!shareCookie) {
            return Collections.emptyList();
        }

        String url = String.format("https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.54.353135d9SjsRTc&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=%s&endTime=%s", start.toDateString(), end.toDateString());
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

            final String mapStr = "orderNo#订单编号,goodsId#商品ID,goodsName#商品信息,unitPrice#商品单价,quantity#商品数,sellerName#所属店铺,payAmount#付款金额,rebateAmount#效果预估,settleAmount#预估收入,status#订单状态,createTime#创建时间";
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
                switch (json.getString("status").trim()) {
                    case "订单结算":
                        order.setStatus(OrderStatus.Settlement);
                        break;
                    case "订单成功":
                        order.setStatus(OrderStatus.Success);
                        break;
                    case "订单付款":
                        order.setStatus(OrderStatus.Paid);
                        break;
                    default:
                        order.setStatus(OrderStatus.Invalid);
                        break;
                }
                orders.add(order);
            }
        } catch (Exception e) {
            log.error("readExcel", e);
            keepLogin(true);
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

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s&_t=%s", HttpCaller.encodeUrl(goodsInfo.getName().trim()), System.currentTimeMillis());
        try (LogWriter log = new LogWriter(TbMedia.log)) {
            log.setPrefix(this.getType().name());
            log.info("findAdv step1 {}", url);
            return caller.invokeSelf(caller -> {
                caller.navigateUrl(url, ".box-btn-left", 4, p -> !caller.hasElement(".bg-search-empty"));
                List<String> eGoodUrls = caller.elementsAttr(".color-m", "href").toList();
                List<String> eMoneys = caller.elementsText(".number-16").toList();
                log.info("findAdv step2-1 goodUrls: {}\tmoneys: {}", eGoodUrls.size(), eMoneys.size());
                for (int i = 0; i < eGoodUrls.size(); i++) {
                    String goodsId = goodsInfo.getId().trim(),
                            eGoodId = HttpUrl.get(eGoodUrls.get(i)).queryParameter("id");
                    log.info("findAdv step2-2 {} {}=={}", goodsInfo.getSellerName(), goodsId, eGoodId);
                    if (!goodsId.equals(eGoodId)) {
                        continue;
                    }

                    int offset = i * 3;
                    goodsInfo.setPrice(eMoneys.get(offset).trim());
                    goodsInfo.setRebateRatio(eMoneys.get(offset + 1).trim());
                    goodsInfo.setRebateAmount(eMoneys.get(offset + 2).trim());

                    caller.executeScript("$('.box-btn-left:eq(" + i + ")').click();");
                    log.info("findAdv step3-1 ok");

                    try {
                        caller.elementClick("button[mx-click=submit]", true);
                        log.info("findAdv step3-2 ok");

                        caller.waitElementLocated("#clipboard-target", 4, 1, p -> {
                            try {
                                caller.elementClick("button[mx-click=submit]");
                                log.info("findAdv btn32 reClick ok");
                                return true;
                            } catch (Exception e) {
                                log.info("findAdv btn32 reClick fail");
                                return false;
                            }
                        });
                        goodsInfo.setCouponAmount("0");
                        Future<String> future = null;
                        String couponUrl = caller.elementVal("#clipboard-target-2");
                        if (!Strings.isNullOrEmpty(couponUrl)) {
                            if (!couponUrl.startsWith("http")) {
                                log.info("findAdv step3-2-2 couponUrl fail and retry");
                                couponUrl = caller.executeScript("return $('#clipboard-target-2').val();");
                            }
                            if (Strings.isNullOrEmpty(couponUrl) || !couponUrl.startsWith("http")) {
                                log.info("findAdv step3-2-2 couponUrl is null -> {}", toJsonString(goodsInfo));
                                return "0";
                            }
                            String $couponUrl = couponUrl;
                            future = TaskFactory.run(() -> {
                                log.info("findAdv step3-2-2 couponUrl {}", $couponUrl);
                                return findCouponAmount($couponUrl);
                            });
                        }

                        caller.waitElementLocated("#magix_vf_code");
                        caller.executeScript("$('.tab-nav li:eq(3)').click();return true;");
                        log.info("findAdv step3-3 ok");

                        String code = caller.elementVal("#clipboard-target,#clipboard-target-2");

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
                        log.error("findAdv", e);
                        keepLogin(false);
                    }
                }
                log.info("Goods {} not found", goodsInfo.getName());
                return null;
            });
        }
    }

    @SneakyThrows
    @Override
    public String findCouponAmount(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            return caller.navigateUrl(url, ".coupons-price").first().getText().trim();
        }));
    }

    @SneakyThrows
    @Override
    public GoodsInfo findGoods(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                WebElement hybridElement = caller.navigateUrl(url, ".tb-main-title,input[name=title]").first();
                String currentUrl = caller.getCurrentUrl();
                if (currentUrl.contains(".taobao.com/")) {
                    String name = hybridElement.getText().trim();
                    String statusString = caller.elementText(".tb-stuff-status");
                    if (!Strings.isNullOrEmpty(statusString)) {
                        name = name.substring(statusString.trim().length() + 1);
                    }
                    goodsInfo.setName(name);
                    String g_config = caller.executeScript("return [g_config.shopId,g_config.shopName].toString()");
                    String[] strings = g_config.split(",");
                    require(strings, strings.length == 2);
                    goodsInfo.setSellerId(strings[0]);
                    goodsInfo.setSellerName(strings[1]);
                } else {
                    goodsInfo.setName(hybridElement.getAttribute("value"));
                    goodsInfo.setSellerId(caller.elementVal("input[name=seller_id]").trim());
                    goodsInfo.setSellerName(caller.elementVal("input[name=seller_nickname]").trim());
                }
                goodsInfo.setImageUrl(caller.elementAttr("#J_ImgBooth", "src"));
                goodsInfo.setId(HttpUrl.get(currentUrl).queryParameter("id"));
                log.info("FindGoods {}\n -> {} -> {}", url, currentUrl, toJsonString(goodsInfo));
                return goodsInfo;
            } catch (Exception e) {
                log.error("findGoods", e);
                return null;
            }
        }));
    }

    @Override
    public String findLink(String content) {
        int start = content.indexOf("http"), end;
        if (start == -1) {
            log.info("Http flag not found {}", content);
            return null;
        }
        String url;
        end = content.indexOf(" ", start);
        if (end == -1) {
            url = content;
        } else {
            url = content.substring(start, end);
        }
        try {
            HttpUrl httpUrl = HttpUrl.get(url);
            if (NQuery.of("tmall.com", "taobao.com", "yukhj.com", "tb.cn").contains(httpUrl.topPrivateDomain())) {
                return url;
            }
        } catch (Exception e) {
            log.info("Http domain not found {} {}", url, e.getMessage());
        }
        log.info("Http flag not found {}", content);
        return null;
    }

    @SneakyThrows
    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            String selector = "#J_SubmitQuick";
            caller.navigateUrl(loginUrl, selector);
            caller.waitClickComplete(selector, 6, s -> caller.getCurrentUrl().startsWith("https://login.taobao.com"), null);

//                caller.navigateUrl("https://pub.alimama.com/myunion.htm");
//                String url;
//                while ((url = caller.getCurrentUrl()).startsWith("https://www.alimama.com/member/login.htm")) {
//                    log.info("please login {}", url);
//                    isLogin = false;
//                    Thread.sleep(1000);
//                }
//                if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com/myunion.htm")) {
//                    login();
//                };

            log.info("login ok...");
            if (shareCookie) {
                caller.syncCookie();
            }
            isLogin = true;
        });
    }

    @SneakyThrows
    private void keepLogin(boolean skipIfBusy) {
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
        }, skipIfBusy);
    }
}
