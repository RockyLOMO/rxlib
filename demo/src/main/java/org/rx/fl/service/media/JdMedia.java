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
//        caller.setShareCookie(true);
    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        String url = "https://union.jd.com/api/report/queryOrderDetail";
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


        String result = caller.post("https://union.jd.com/api/report/queryOrderDetail", param);
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
//            List<WebElement> ePrices = caller.findElements(By.cssSelector(".three")).toList();
                for (int i = 0; i < eIds.size(); i++) {
                    WebElement eId = eIds.get(i);
                    String goodsUrl = eId.getAttribute("href");
                    String goodsId = getGoodsId(goodsUrl);
                    if (!goodsId.equals(goodsInfo.getId())) {
                        continue;
                    }

//                goodsInfo.setPrice(ePrices.get(i).getText().trim());
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
            caller.syncCookie();
            isLogin = true;
        });
    }

    private void keepLogin() {

    }
}
