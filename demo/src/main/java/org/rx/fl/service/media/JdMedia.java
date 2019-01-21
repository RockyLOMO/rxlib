package org.rx.fl.service.media;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.LogWriter;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;
import org.rx.util.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static org.rx.common.Contract.toJsonString;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class JdMedia implements Media {
    @Getter
    private volatile boolean isLogin;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Jd;
    }

    public JdMedia() {
        caller = new WebCaller();
    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        login();
        String url = "http://xunion.jd.com/api/report/queryOrderDetail";
        caller.invokeSelf(caller -> {
caller.navigateUrl(url);
            ".has-gutter th";
        }, true);
        String param = String.format("{\"data\":{\"endTime\":\"%s\",\"opType\":\"1\",\"orderId\":0,\"orderStatus\":\"0\",\"orderType\":\"0\",\"startTime\":\"%s\",\"unionTraffictType\":\"0\"},\"pageNo\":1,\"pageSize\":20}", start.toDateString(), end.toDateString());

//        caller.invokeSelf(caller -> {
//            String script = String.format("$.ajax({\n" +
//                    "    type: \"post\",\n" +
//                    "    url: \"%s\",\n" +
//                    "    data: JSON.stringify(%s),\n" +
//                    "    async: false,\n" +
//                    "    contentType: \"application/json; charset=utf-8\",\n" +
//                    "    dataType: \"json\",\n" +
//                    "    success: function (data) {\n" +
//                    "        window._x = data;\n" +
//                    "    }\n" +
//                    "});\n" +
//                    "return window._x;", url, param);
//            System.out.println(script);
//            String result = caller.executeScript(script);
//            System.out.println(result);
//        }, true);

        HttpCaller caller = new HttpCaller();

        String rawCookie = "__jdv=209449046|direct|-|none|-|1547533053480; __jdu=15475330534791632634422; 3AB9D23F7A4B3C9B=MKWBZ3GMH6HVTHDORPUB4GOFXUCVZGFZKQ3VGCLKJSBCZKPE7QJURV3VJQE7NTYD5MFUTKKTOW2LVM5GFQW3JSEWSA; TrackID=1ttlj0-zhO8tvgfOESdNMvWuFlEgzgBnjjXJ34enXidDnMuPV-PueWLY4QYY311ZG_yg4usaEnncb36xYMgytkg; pinId=MNQEl-BugMlGID1tlqy4kA; unick=%E9%97%B2%E7%9D%80%E7%BD%91%E8%B4%AD; ceshi3.com=201; _tp=rQSihfQT%2FVwcShkp%2FHvuMg%3D%3D; logining=1; _pst=youngcoder; __jda=95931165.15475330534791632634422.1547533053.1547543704.1548065470.4; __jdc=95931165; __jdb=95931165.10.15475330534791632634422|4.1548065470";
        log.info("findOrders load cookie: {}", rawCookie);
        caller.setHeaders(HttpCaller.parseOriginalHeader("Connection: keep-alive\n" +
                "Pragma: no-cache\n" +
                "Cache-Control: no-cache\n" +
                "Accept: application/json, text/plain, */*\n" +
                "Origin: https://union.jd.com\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36\n" +
                "Content-Type: application/json;charset=UTF-8\n" +
                "Referer: https://union.jd.com/order\n" +
                "Accept-Encoding: gzip, deflate, br\n" +
                "Accept-Language: en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7\n" +
                "Cookie: shshshfpa=71d42fb4-d6e1-8245-ddf3-6cba2b268ad2-1533007071; sid=90d40d6dfe42cd29b2dd1273b646b0a0; shshshfpb=1125e480b4a0d42aea46184f431a3f60b03bdcaabc75b03185ae9895c0; ipLoc-djd=12-988-53563-0.137838736; sidebarStatus=1; ssid=\"1iwP9LhkTyuVK8lArcQzxw==\"; login=true; V_youngcoder_overview=true; V_youngcoder_promotion=true; V_youngcoder_webMng=true; V_youngcoder_socialPromotion=true; MMsgIdyoungcoder=19401; unick=%E9%97%B2%E7%9D%80%E7%BD%91%E8%B4%AD; ceshi3.com=201; _tp=rQSihfQT%2FVwcShkp%2FHvuMg%3D%3D; _pst=youngcoder; mt_xid=V2_52007VwMWUlxZUlMaQB9aDWMAElNYX1ZcGUwebABuBBRaWV1WRkxKS14ZYgFHBUFQAl1IVRBUAmQHFwBYXgVaGnkaXQVvHxNSQVhWSx9AEl0GbAAVYl9oUWocSB9UAGIzElZc; ipLocation=%u6c5f%u82cf; V_jd_759f867040f60_socialPromotion=true; pinId=MNQEl-BugMlGID1tlqy4kA; pin=youngcoder; unpl=V2_ZzNtbRJVFkchXRRUeE5UAmJRFQpLURcddg5BUHgdVANlAxZZclRCFX0UR1RnGVsUZwMZX0NcQhBFCEdkexhdBGYKGlRKVXMVcQ8oVRUZVQBXMxFdclZzFXEIR1V7EFkMYQUaWUFXQhByDEBSfh5sNWcLFm1ygOq7o4TggMKzi7flMxZbQVFHFHQJRWR6KV01LG0TEEJTQxR0CE9Rch9aDWMAElxHUEcTcw1BZHkZXQFhMxE%3d; __jdv=122270672%7Ckong%7Ct_1000027277_101745%7Czssc%7C06297e7f-9a66-4321-95ee-e1f1cc889f0c-p_1999-pr_1524-at_101745%7C1547609432093; PCSYCityID=2; __jdu=15100184877952104657746; user-key=3fee60c2-bdf7-47c6-af2b-1c890f506a57; TrackID=1TMxTSkdRiBbsUP9PbOCFEP56jFaUD_mdteqMnb5T0RqTBANfULJK_oyh3rzlD2ROaH_R4vIJzbz3llhayxC7-tUcVUzCEcfLyEG8zw_KKIY; MNoticeIdyoungcoder=195; cn=9; _gcl_au=1.1.241906024.1548058992; shshshfp=d4bb58ff8a6120b10afb10355f98d50d; 3AB9D23F7A4B3C9B=7DE5L4U3UDBSA73JGSN2QSD5QD5OMTEPMLI5RICC7TJFD5QJGDXEA5XQRU2XB5YCMCLNSIFODR7RNYJNYE7ZO4EHHM; __jdc=209449046; thor=95815463ABE92DC8E3947CE59B225B4BCB3D6DFCC5B5FD47848598BD5F745A7FD5A93A6BA2AC84ED4825992720EE5749434E200091E64DF71EE2C0581C446F9FD4BD77351FD9977CAE3AE149E3B56AC2B987B718EF16B49EFF5EB5E6D92453CAD8DA6DF6963E7AC2271951951AD90F3E295120B49E28D92D285991122EED3204C28CCEA7B1C44DF027549B550B56450E; __jda=209449046.15100184877952104657746.1510018488.1548058992.1548066939.333; __jdb=209449046.5.15100184877952104657746|333.1548066939"));
        String result = caller.post(url, param);
        System.out.println(result);
//        JSONObject json = JSON.parseObject(result).getJSONObject("data");
//        if (json == null) {
        return Collections.emptyList();
//        }
//
//        List<OrderInfo> list = new ArrayList<>();
//        JSONArray array = json.getJSONArray("orderDetailInfos");
//        for (int i = 0; i < array.size(); i++) {
//            JSONObject item = array.getJSONObject(i);
//            OrderInfo orderInfo = JsonMapper.Default.convert("jdQueryOrderDetail", item, OrderInfo.class);
//            list.add(orderInfo);
//        }
//        return list;
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://union.jd.com/#/proManager/index?keywords=%s&pageNo=1", HttpCaller.encodeUrl(goodsInfo.getName().trim()));
        try (LogWriter log = new LogWriter(JdMedia.log)) {
            log.setPrefix(this.getType().name());
            log.info("findAdv step1 {}", url);
            return caller.invokeSelf(caller -> {
                By idBy = By.cssSelector(".imgbox");
                caller.navigateUrl(url, idBy);
                List<WebElement> eIds = caller.findElements(idBy).toList();
                for (int i = 0; i < eIds.size(); i++) {
                    WebElement eId = eIds.get(i);
                    String goodsUrl = eId.getAttribute("href");
                    String goodsId = getGoodsId(goodsUrl);
                    if (!goodsId.equals(goodsInfo.getId())) {
                        continue;
                    }

                    String text = caller.executeScript("$(\".card-button:eq(" + i + ")\").click();" +
                            "return [$(\".three:eq(" + i + ") span:first\").text(),$(\".one:eq(" + i + ") b\").text()].toString();");
                    log.info("findAdv step2 ok");
                    String[] strings = text.split(",");
                    goodsInfo.setPrice(strings[0].trim());
                    String rebateStr = strings[1];
                    int j = rebateStr.indexOf("%");
                    goodsInfo.setRebateRatio(rebateStr.substring(0, j++).trim());
                    goodsInfo.setRebateAmount(rebateStr.substring(j).trim());

                    String[] combo = {"#socialPromotion", "input[placeholder=请选择社交媒体]", ".el-select-dropdown__item:last",
                            "input[placeholder=请输入推广位]", ".el-select-dropdown__item:last", ".operation:last .el-button--primary"};
                    for (int k = 0; k < combo.length; k++) {
                        String x = combo[k];
                        if (k == 2 || k == 4 || k == 5) {
                            caller.executeScript(String.format("$(\"%s\").click();", x));
                        } else {
                            caller.waitElementLocated(By.cssSelector(x)).first().click();
                        }
                        log.info("findAdv step3 combo({}) click..", x);
                    }

                    By waiter = By.cssSelector("#pane-0 input");
                    NQuery<WebElement> codes = caller.waitElementLocated(waiter);
                    goodsInfo.setCouponAmount("0");
                    Future<String> future = null;
                    if (codes.count() == 2) {
                        String couponUrl = codes.last().getAttribute("value");
                        future = TaskFactory.run(() -> {
                            log.info("findAdv step3-2 couponUrl {}", couponUrl);
                            return findCouponAmount(couponUrl);
                        });
                    }
                    String code = codes.last().getAttribute("value");

                    if (future != null) {
                        try {
                            goodsInfo.setCouponAmount(future.get());
                        } catch (Exception e) {
                            log.info("get coupon amount result fail -> {}", e.getMessage());
                        }
                    }
                    log.info("Goods {} -> {}", toJsonString(goodsInfo), code);
                    return code;
                }
                log.info("Goods {} not found", goodsInfo.getName());
                return null;
            });
        }
    }

    @Override
    public String findCouponAmount(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            By first = By.cssSelector(".price span");
            caller.navigateUrl(url, first);
            return caller.findElement(first).getText().trim();
        }));
    }

    @SneakyThrows
    @Override
    public GoodsInfo findGoods(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                By hybridSelector = By.cssSelector(".sku-name,.shop_intro h2");
                caller.navigateUrl(url, hybridSelector);
                WebElement hybridElement = caller.findElement(hybridSelector);
                goodsInfo.setName(hybridElement.getText().trim());
                WebElement eSeller = caller.findElement(By.cssSelector(".name:last-child"), false);
                if (eSeller != null) {
                    goodsInfo.setSellerName(eSeller.getText().trim());
                }
                String currentUrl = caller.getCurrentUrl();
                if (currentUrl.contains("re.jd.com/")) {
                    goodsInfo.setImageUrl(caller.findElement(By.cssSelector(".focus_img")).getAttribute("src"));
                } else {
                    goodsInfo.setImageUrl(caller.findElement(By.cssSelector("#spec-img")).getAttribute("src"));
                }
                goodsInfo.setId(getGoodsId(currentUrl));
                log.info("FindGoods {}\n -> {} -> {}", url, currentUrl, toJsonString(goodsInfo));
                return goodsInfo;
            } catch (Exception e) {
                log.error("findGoods", e);
                return null;
            }
        }));
    }

    private String getGoodsId(String url) {
        int start = url.lastIndexOf("/"), end = url.lastIndexOf(".");
        return url.substring(start + 1, end);
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
            if (NQuery.of("jd.com").contains(httpUrl.topPrivateDomain())) {
                return url;
            }
        } catch (Exception e) {
            log.info("Http domain not found {} {}", url, e.getMessage());
        }
        log.info("Http flag not found {}", content);
        return null;
    }

    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            String advUrl = "https://union.jd.com/#/order", loginUrl = "https://union.jd.com/#/login";
            caller.navigateUrl(advUrl, By.cssSelector("body"));
            Predicate<Object> doLogin = s -> !caller.getCurrentUrl().equals(loginUrl);
            caller.wait(2, 500, doLogin, null);
            if (doLogin.test(null)) {
                try {
                    caller.executeScript("$(\"#loginname\",$(\"#indexIframe\")[0].contentDocument).val(\"youngcoder\");" +
                            "$(\"#nloginpwd\",$(\"#indexIframe\")[0].contentDocument).val(\"jinjin&R4ever\");");
                    caller.waitClickComplete(By.cssSelector("#paipaiLoginSubmit"), 10, s -> caller.getCurrentUrl().startsWith(loginUrl), null);
                } catch (Exception e) {
                    log.info("login error {}...", e.getMessage());
                }
            }
            log.info("login ok...");
            isLogin = true;
        });
    }

    private void keepLogin() {

    }
}
