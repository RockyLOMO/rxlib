package org.rx.fl.service.media;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.rx.beans.DateTime;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.WebCaller;
import org.rx.socks.http.HttpClient;

import java.net.URL;
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
        return null;
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        login();
        String url = String.format("https://union.jd.com/#/proManager/index?keywords=%s&pageNo=1", HttpCaller.encodeUrl(goodsInfo.getName().trim()));
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
                        "return [$(\".three:eq(" + i + ")\").text(),$(\".one:eq(" + i + ") b\").text()].toString();");
                String[] strings = text.split(",");
                goodsInfo.setPrice(strings[0].trim());
                String rebateStr = strings[1];
                int j = rebateStr.indexOf("%");
                goodsInfo.setRebateRatio(rebateStr.substring(0, j++).trim());
                goodsInfo.setRebateAmount(rebateStr.substring(j).trim());


//                goodsInfo.setCouponAmount("0");
//                Future<String> future = null;
//
//                future = TaskFactory.run(() -> {
//                    log.info("findAdv step4-2-2 couponUrl {}", $couponUrl);
//                    return findCouponAmount($couponUrl);
//                });
//
//                if (future != null) {
//                    try {
//                        goodsInfo.setCouponAmount(future.get());
//                    } catch (Exception e) {
//                        log.info("get coupon amount result fail -> {}", e.getMessage());
//                    }
//                }
            }
            log.info("Goods {} not found", goodsInfo.getName());
            return null;
        });
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
                caller.executeScript("$(\"#loginname\",$(\"#indexIframe\")[0].contentDocument).val(\"youngcoder\");" +
                        "$(\"#nloginpwd\",$(\"#indexIframe\")[0].contentDocument).val(\"jinjin&R4ever\");");
                caller.waitClickComplete(By.cssSelector("#paipaiLoginSubmit"), 10, s -> caller.getCurrentUrl().startsWith(loginUrl), null);
            }
            log.info("login ok...");
            isLogin = true;
        });
    }

    private void keepLogin() {

    }
}
