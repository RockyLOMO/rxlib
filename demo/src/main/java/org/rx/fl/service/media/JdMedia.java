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
import org.rx.fl.util.WebCaller;
import org.rx.socks.http.HttpClient;

import java.net.URL;
import java.util.List;
import java.util.function.Predicate;

import static org.rx.common.Contract.toJsonString;

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
        return null;
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
                //re.jd.com & jd.com
                goodsInfo.setImageUrl(caller.findElement(By.cssSelector("#spec-img")).getAttribute("src"));
                String currentUrl = caller.getCurrentUrl();
                int start = currentUrl.lastIndexOf("/"), end = currentUrl.lastIndexOf(".");
                goodsInfo.setId(currentUrl.substring(start + 1, end));
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
        int start = content.indexOf("http"), end;
        if (start == -1) {
            log.info("Http start flag not found {}", content);
            return null;
        }
        end = content.indexOf(" ", start);
        if (end == -1) {
            String url = String.format(content, start);
            try {
                HttpUrl httpUrl = HttpUrl.get(url);
                if (NQuery.of("jd.com").contains(httpUrl.topPrivateDomain())) {
                    return url;
                }
            } catch (Exception e) {
                log.info("Http domain not found {} {}", url, e.getMessage());
            }
            log.info("Http end flag not found {}", content);
            return null;
        }
        return content.substring(start, end);
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
                caller.findElement(By.cssSelector("#loginname")).sendKeys("");
                caller.findElement(By.cssSelector("#nloginpwd")).sendKeys("");
                caller.waitClickComplete(By.cssSelector("#paipaiLoginSubmit"), 6, s -> caller.getCurrentUrl().startsWith(loginUrl), null);
            }
            log.info("login ok...");
            isLogin = true;
        });
    }

    private void keepLogin() {

    }
}
