package org.rx.fl.service.media;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.json.JSONArray;
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
import org.rx.util.function.Func;

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
            String btnSelector = ".find-order-btn";
            caller.navigateUrl(url, btnSelector);

            caller.elementClick("input[placeholder=开始日期]");
            Func<Integer> getLength = () -> App.changeType(caller.executeScript("return $('.available').length"), int.class);
            int availableLength = getLength.invoke();
            int days = App.changeType(end.subtract(start).getTotalDays(), int.class);
            if (availableLength < days) {
                caller.elementClick(".el-icon-arrow-left");
                availableLength = getLength.invoke();
            }
            int maxOffset = availableLength - 1;
            caller.executeScript(String.format("$('.available:eq(%s)').click();$('.available:eq(%s)').click();", maxOffset - days, maxOffset));

            caller.elementClick(btnSelector);
            Thread.sleep(1000);

            String json = caller.executeScript("        var thArr = [];\n" +
                    "        $(\".has-gutter th\").each(function (i, o) {\n" +
                    "            thArr.push($(o).text());\n" +
                    "        });\n" +
                    "        thArr.length--;\n" +
                    "        var trArr = [];\n" +
                    "        trArr.push(thArr);\n" +
                    "        $(\".el-table__body tr\").each(function (i, o) {\n" +
                    "            var tdArr = [];\n" +
                    "            $(o).find(\"td\").each(function () {\n" +
                    "                tdArr.push($(arguments[1]).text());\n" +
                    "            });\n" +
                    "            trArr.push(tdArr);\n" +
                    "        });\n" +
                    "        return JSON.stringify(trArr);");
            JSONArray jArray = JSONArray.parse(json);
            List cols = jArray.getJSONArray(0).toList();
            for (int i = 1; i < jArray.length(); i++) {
                jArray.getJSONArray(i);
            }
            "无效-拆单";
            List<String> cols = caller.elementsText(".has-gutter th").toList();
            NQuery<WebElement> colElms = caller.waitElementLocated(".has-gutter th");
        }, true);
        return null;
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://union.jd.com/#/proManager/index?keywords=%s&pageNo=1", HttpCaller.encodeUrl(goodsInfo.getName().trim()));
        try (LogWriter log = new LogWriter(JdMedia.log)) {
            log.setPrefix(this.getType().name());
            log.info("findAdv step1 {}", url);
            return caller.invokeSelf(caller -> {
                List<WebElement> eIds = caller.navigateUrl(url, ".imgbox").toList();
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
                            caller.elementClick(x, true);
                        }
                        log.info("findAdv step3 combo({}) click..", x);
                    }

                    NQuery<WebElement> codes = caller.waitElementLocated("#pane-0 input");
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
            return caller.navigateUrl(url, ".price span").first().getText().trim();
        }));
    }

    @SneakyThrows
    @Override
    public GoodsInfo findGoods(String url) {
        return getOrStore(url, k -> caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                WebElement hybridElement = caller.navigateUrl(url, ".sku-name,.shop_intro h2").first();
                goodsInfo.setName(hybridElement.getText().trim());
                String eSeller = caller.elementText(".name:last-child");
                if (!Strings.isNullOrEmpty(eSeller)) {
                    goodsInfo.setSellerName(eSeller.trim());
                }
                String currentUrl = caller.getCurrentUrl();
                if (currentUrl.contains("re.jd.com/")) {
                    goodsInfo.setImageUrl(caller.elementAttr(".focus_img", "src"));
                } else {
                    goodsInfo.setImageUrl(caller.elementAttr("#spec-img", "src"));
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
            caller.navigateUrl(advUrl, "body");
            Predicate<Object> doLogin = s -> !caller.getCurrentUrl().equals(loginUrl);
            caller.wait(2, 500, doLogin, null);
            if (doLogin.test(null)) {
                try {
                    caller.executeScript("$(\"#loginname\",$(\"#indexIframe\")[0].contentDocument).val(\"youngcoder\");" +
                            "$(\"#nloginpwd\",$(\"#indexIframe\")[0].contentDocument).val(\"jinjin&R4ever\");");
                    caller.waitClickComplete("#paipaiLoginSubmit", 10, s -> caller.getCurrentUrl().startsWith(loginUrl), null);
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
