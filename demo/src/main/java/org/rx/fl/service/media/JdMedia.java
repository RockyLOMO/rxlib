package org.rx.fl.service.media;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.openqa.selenium.WebElement;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.common.*;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;
import org.rx.util.JsonMapper;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.beans.$.$;
import static org.rx.common.Contract.toJsonString;
import static org.rx.fl.util.WebCaller.BodySelector;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class JdMedia implements Media {
    private static final String loginUrl = "https://union.jd.com/login";
    private static final String[] keepLoginUrl = {"https://union.jd.com/order",
            "https://union.jd.com/report",
            "https://union.jd.com/accountingCenter",
            "https://union.jd.com/accounts"};

    @Getter
    private volatile boolean isLogin;
    private JdLogin helper;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Jd;
    }

    public JdMedia() {
        int loginPort = App.readSetting("app.media.jd.loginPort");
        helper = new JdLogin(loginPort);
        caller = new WebCaller();
        int period = App.readSetting("app.media.jd.keepLoginSeconds");
        TaskFactory.schedule(() -> keepLogin(true), 2 * 1000, period * 1000, this.getType().name());
    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        login();
        return caller.invokeSelf(caller -> {
            caller.navigateUrl("https://union.jd.com/order", BodySelector);

            int pageNo = 1, pageSize = 80;
            String jData = String.format("{\n" +
                    "                \"data\": {\n" +
                    "                    \"endTime\": \"%s\",\n" +
                    "                    \"opType\": \"1\",\n" +
                    "                    \"orderId\": 0,\n" +
                    "                    \"orderStatus\": \"0\",\n" +
                    "                    \"orderType\": \"0\",\n" +
                    "                    \"startTime\": \"%s\",\n" +
                    "                    \"unionTraffictType\": \"0\"\n" +
                    "                }, \"pageNo\": %s, \"pageSize\": %s\n" +
                    "            }", end.toDateString(), start.toDateString(), pageNo, pageSize);
            log.info("findOrders data\n{}", jData);

            List<OrderInfo> list = new ArrayList<>();
            JSONObject json;
            do {
                String callback = caller.executeScript(String.format("$.ajax({\n" +
                        "            type: \"post\",\n" +
                        "            url: \"https://union.jd.com/api/report/queryOrderDetail\",\n" +
                        "            data: JSON.stringify(%s),\n" +
                        "            async: false,\n" +
                        "            contentType: \"application/json; charset=utf-8\",\n" +
                        "            dataType: \"json\",\n" +
                        "            success: function (data) {\n" +
                        "                // console.log(data);\n" +
                        "                window._callbackValue = data;\n" +
                        "            }\n" +
                        "        });\n" +
                        "        var json = {error: false}, result = window._callbackValue;\n" +
                        "        if (result.code != 200) {\n" +
                        "            json.error = true;\n" +
                        "            return JSON.stringify(json);\n" +
                        "        }\n" +
                        "        result = result.data;\n" +
                        "        json.moreData = result.moreData;\n" +
                        "        json.orders = [];\n" +
                        "        var orderDetailInfos = result.orderDetailInfos;\n" +
                        "        for (var i = 0; i < orderDetailInfos.length; i++) {\n" +
                        "            var item = orderDetailInfos[i];\n" +
                        "            if (item.validCodeStr == \"无效-拆单\") {\n" +
                        "                continue;\n" +
                        "            }\n" +
                        "            var order = {};\n" +
                        "            order.orderNo = item.orderId;\n" +
                        "            order.goodsId = item.orderSkuDetailInfos[0].skuId;\n" +
                        "            order.goodsName = item.orderSkuDetailInfos[0].skuName;\n" +
                        "            order.unitPrice = item.orderSkuDetailInfos[0].price;\n" +
                        "            order.quantity = item.orderSkuDetailInfos[0].skuNum;\n" +
                        "            order.sellerName = \"\";\n" +
                        "            order.payAmount = item.payPriceSum;\n" +
                        "            order.rebateAmount = item.estimateFeeOrder;\n" +
                        "            order.settleAmount = item.actualFeeOrder;\n" +
                        "            order.createTime = item.orderTimeStr;\n" +
                        "            order.validCodeStr = item.validCodeStr;\n" +
                        "            json.orders.push(order);\n" +
                        "        }\n" +
                        "        return JSON.stringify(json);", jData));
                log.info("findOrders callbackValue {}", callback);
                json = JSON.parseObject(callback);
                if (json.getBooleanValue("error")) {
                    log.info("check login");
                    keepLogin(false);
                    break;
                }
                JSONArray orders = json.getJSONArray("orders");
                log.info("findOrders get {} orders", orders.size());
                for (int i = 0; i < orders.size(); i++) {
                    JSONObject row = orders.getJSONObject(i);
                    //spring boot error
//                        OrderInfo order = JsonMapper.Default.convertTo(OrderInfo.class, "jdQueryOrderDetail", row);
                    OrderInfo order = row.toJavaObject(OrderInfo.class);
                    order.setMediaType(this.getType());
                    switch (row.getString("validCodeStr").trim()) {
                        case "已结算":
                            order.setStatus(OrderStatus.Settlement);
                            break;
                        case "已完成":
                            order.setStatus(OrderStatus.Success);
                            break;
                        case "已付款":
                            order.setStatus(OrderStatus.Paid);
                            break;
                        default:
                            order.setStatus(OrderStatus.Invalid);
                            break;
                    }
                    list.add(order);
                }
                pageNo++;
            } while (json.getBooleanValue("moreData"));
            return list;
        }, true);
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://union.jd.com/#/proManager/index?keywords=%s&pageNo=1", HttpCaller.encodeUrl(goodsInfo.getName().trim()));
        try (LogWriter log = new LogWriter(JdMedia.log)) {
            log.setPrefix(this.getType().name());
            log.info("findAdv step1 {}", url);
            return caller.invokeSelf(caller -> {
                List<WebElement> eIds = caller.navigateUrl(url, ".imgbox,.nodata").toList();
                for (int i = 0; i < eIds.size(); i++) {
                    WebElement eId = eIds.get(i);
                    String goodsUrl = eId.getAttribute("href");
                    String goodsId = getGoodsId(goodsUrl);
                    if (!goodsId.equals(goodsInfo.getId())) {
                        continue;
                    }

                    //rect需要focus才呈现
                    caller.setWindowRectangle(new Rectangle(0, 0, 400, 300));
                    String btn1Selector = String.format(".card-button:eq(%s)", i * 2 + 1);
                    String text = caller.executeScript(String.format("$('%s').click();" +
                            "return [$('.three:eq(%s) span:first').text(),$('.one:eq(%s) b').text()].toString();", btn1Selector, i, i));
                    log.info("findAdv step2 ok");
                    String[] strings = text.split(",");
                    goodsInfo.setPrice(strings[0].trim());
                    String rebateStr = strings[1];
                    int j = rebateStr.indexOf("%");
                    goodsInfo.setRebateRatio(rebateStr.substring(0, j++).trim());
                    goodsInfo.setRebateAmount(rebateStr.substring(j).trim());

                    goodsInfo.setCouponAmount("0");
                    Future<String> future = null;
                    $<Tuple<Boolean, String>> $code = $();
                    caller.waitClickComplete(7, p -> {
                        String selector = ".el-input__inner";
                        List<String> vals = caller.elementsVal(selector).toList();
                        if (vals.isEmpty()) {
                            throw new InvalidOperationException(String.format("Wait %s missing.. -> %s", selector, url));
                        }
                        log.info("Codes values: {}", String.join(",", vals));
                        boolean hasCoupon = vals.size() == 10;
                        String xCode = vals.get(hasCoupon ? 7 : 6);
                        $code.$ = Tuple.of(hasCoupon, xCode);
                        return !Strings.isNullOrEmpty(xCode) && xCode.startsWith("http");
                    }, btn1Selector, 3, true);
                    String code = $code.$.right;
                    if ($code.$.left) {
                        future = TaskFactory.run(() -> {
                            log.info("findAdv step3-2 couponUrl {}", code);
                            return findCouponAmount(code);
                        });
                    }

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
            return caller.navigateUrl(url, ".price span").first().getText().trim();
        }));
    }

    @SneakyThrows
    @Override
    public GoodsInfo findGoods(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                WebElement hybridElement = caller.navigateUrl(url, ".sku-name,.shop_intro h2,#itemName").first();
                goodsInfo.setName(hybridElement.getText().trim());
                String eSeller = caller.elementText(".name:last,._n");
                if (!Strings.isNullOrEmpty(eSeller)) {
                    goodsInfo.setSellerName(eSeller.trim());
                }
                String currentUrl = caller.getCurrentUrl();
                if (currentUrl.contains("re.jd.com/")) {
                    goodsInfo.setImageUrl(caller.elementAttr(".focus_img", "src"));
                } else {
                    goodsInfo.setImageUrl(caller.elementAttr("#spec-img,#firstImg", "src"));
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

    @SneakyThrows
    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            caller.navigateUrl(keepLoginUrl[0], BodySelector);
            caller.waitComplete(2, i -> caller.getCurrentUrl().equals(loginUrl), false);
            while (caller.getCurrentUrl().equals(loginUrl)) {
//                caller.executeScript("var doc = $(\"#indexIframe\")[0].contentDocument;\n" +
//                        "        $(\"#loginname\", doc).val(\"17091916400\");\n" +
//                        "        $(\"#nloginpwd\", doc).val(window.atob(\"amluamluJlI0ZXZlcg==\"));\n" +
//                        "        $(\"#paipaiLoginSubmit\", doc).click();");
//                caller.waitComplete(8, i -> !caller.getCurrentUrl().equals(loginUrl), false);
//
                try {
                    String key = helper.produceKey();
                    log.info("produce key {}", key);
                    caller.navigateUrl(key, BodySelector);
                    log.info("consume key {}", caller.getCurrentUrl());

                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.error("login", e);
                }
            }
            log.info("login ok...");
            isLogin = true;
        });
    }

    @SneakyThrows
    private void keepLogin(boolean skipIfBusy) {
        caller.invokeSelf(caller -> {
            String noCache = String.format("?_t=%s", System.currentTimeMillis());
            int i = ThreadLocalRandom.current().nextInt(0, keepLoginUrl.length);
            caller.navigateUrl(keepLoginUrl[i] + noCache);
            if (caller.getCurrentUrl().equals(loginUrl)) {
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
