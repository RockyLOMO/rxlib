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
import org.rx.common.App;
import org.rx.common.LogWriter;
import org.rx.common.NQuery;
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
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Jd;
    }

    public JdMedia() {
        caller = new WebCaller();
        int period = App.readSetting("app.media.jd.keepLoginSeconds");
        TaskFactory.schedule(() -> keepLogin(true), 2 * 1000, period * 1000, this.getType().name());
    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        login();
        return caller.invokeSelf(caller -> {
            caller.navigateUrl("https://union.jd.com/order", "body");

            int pageNo = 1, pageSize = 100;
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
                        "                console.log(data);\n" +
                        "                window._callbackValue = data;\n" +
                        "            }\n" +
                        "        });\n" +
                        "        return JSON.stringify(window._callbackValue);", jData));
                log.info("findOrders callbackValue {}", callback);
                JSONObject jsVal = JSON.parseObject(callback);
                if (jsVal.getIntValue("code") != 200) {
                    keepLogin(false);
                    break;
                }
                json = JSON.parseObject(callback).getJSONObject("data");
                JSONArray orders = json.getJSONArray("orderDetailInfos");
                if (orders.isEmpty()) {
                    break;
                }

                for (int i = 0; i < orders.size(); i++) {
                    JSONObject row = orders.getJSONObject(i);
                    OrderInfo order = JsonMapper.Default.convertTo(OrderInfo.class, "jdQueryOrderDetail", row);
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

                    //fix bug
                    caller.setWindowRectangle(new Rectangle(0, 0, 800, 600));
                    String text = caller.executeScript("$(\".card-button:eq(" + i + 1 + ")\").click();" +
                            "return [$(\".three:eq(" + i + ") span:first\").text(),$(\".one:eq(" + i + ") b\").text()].toString();");
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
                    caller.waitComplete(4, p -> {
                        List<String> vals = caller.elementsVal(".el-input__inner").toList();
                        log.info("Codes values: {}", String.join(",", vals));
                        boolean hasCoupon = vals.size() == 10;
                        String xCode = vals.get(hasCoupon ? 7 : 6);
                        $code.$ = Tuple.of(hasCoupon, xCode);
                        return !Strings.isNullOrEmpty(xCode) && xCode.startsWith("http");
                    }, true);
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

    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            caller.navigateUrl(keepLoginUrl[0], "body");
            caller.waitComplete(2, i -> caller.getCurrentUrl().equals(loginUrl), false);
            if (caller.getCurrentUrl().equals(loginUrl)) {
                caller.executeScript("        var doc = $(\"#indexIframe\")[0].contentDocument;\n" +
                        "        $(\"#loginname\", doc).val(\"17091916400\");\n" +
                        "        $(\"#nloginpwd\", doc).val(window.atob(\"amluamluJlI0ZXZlcg==\"));\n" +
                        "        $(\"#paipaiLoginSubmit\", doc).click();");
                caller.waitComplete(2, i -> !caller.getCurrentUrl().equals(loginUrl), false);
//                Rectangle rectangle = caller.getWindowRectangle();
//                caller.maximize();
//                caller.switchToFrame("#indexIframe");
//                byte[] srcs = Base64.getDecoder().decode(caller.elementAttr(".JDJRV-bigimg img", "src"));
//                BufferedImage bgImg = ImageIO.read(new ByteArrayInputStream(srcs));
//
//                caller.setWindowRectangle(rectangle);
                if (caller.getCurrentUrl().equals(loginUrl)) {
                    login();
                    return;
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
