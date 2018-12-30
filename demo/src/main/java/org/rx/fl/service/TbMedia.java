package org.rx.fl.service;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.rx.InvalidOperationException;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;
import org.rx.fl.util.WebCaller;
import org.rx.util.AsyncTask;

import java.net.URLEncoder;
import java.util.List;

import static org.rx.Contract.toJsonString;

@Slf4j
public class TbMedia implements Media {
    @Getter
    private boolean isLogin;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Taobao;
    }

    public TbMedia() {
        caller = new WebCaller();
        AsyncTask.TaskFactory.schedule(() -> keepLogin(), 30 * 1000);
    }

    @SneakyThrows
    private void checkLogin() {
        if (isLogin) {
            return;
        }
        login();
    }

    @SneakyThrows
    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        checkLogin();
        return caller.invokeSelf(caller -> {
            try {
                String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s", URLEncoder.encode(goodsInfo.getTitle(), "utf-8"));
                log.info("findAdv step1 {}", url);
                By first = By.cssSelector(".box-btn-left");
                caller.navigateUrl(url, first);
                List<WebElement> eBtns = caller.findElements(first).toList();
                List<WebElement> eSellers = caller.findElements(By.cssSelector("a[vclick-ignore]")).skip(1).toList();
                List<WebElement> eMoneys = caller.findElements(By.cssSelector(".number-16")).toList();
                log.info("findAdv step2 btnSize: {}\tsellerEles: {}\tmoneyEles: {}", eBtns.size(), eSellers.size(), eMoneys.size());
                for (int i = 0; i < eSellers.size(); i++) {
                    WebElement eSeller = eSellers.get(i);
                    String sellerName = goodsInfo.getSellerNickname().trim();
                    log.info("findAdv step3 {} == {}", sellerName, eSeller.getText());
                    if (sellerName.equals(eSeller.getText().trim())) {
                        int offset = i * 3;
                        goodsInfo.setPrice(eMoneys.get(offset).getText().trim());
                        goodsInfo.setBackRate(eMoneys.get(offset + 1).getText().trim());
                        goodsInfo.setBackMoney(eMoneys.get(offset + 2).getText().trim());

//                try {
//                    eBtns.get(i).click();
//                } catch (WebDriverException e) {
//                    Logger.info("findAdv %s", e.getMessage());
                        caller.executeScript("$('.box-btn-left:eq(" + i + ")').click();");
//                }
                        log.info("findAdv step4-1 ok");

                        By waiter = By.cssSelector("button[mx-click=submit]");
                        WebElement btn42 = caller.findElements(waiter, waiter).first();
                        Thread.sleep(1000);
                        btn42.click();
//                caller.executeScript("$('button[mx-click=submit]').click();");
                        log.info("findAdv step4-2 ok");

                        By step423 = By.cssSelector("#clipboard-target-2");
                        String couponUrl = null;
                        WebElement eStep423 = caller.findElement(step423, false);
                        if (eStep423 != null) {
                            couponUrl = eStep423.getAttribute("value");
                        }

                        waiter = By.cssSelector("li[mx-click='tab(4)']");
                        WebElement btn43 = caller.findElements(waiter, waiter).first();
                        Thread.sleep(100);
                        btn43.click();
                        log.info("findAdv step4-3 ok");

                        eStep423 = caller.findElement(step423, false);
                        if (eStep423 != null) {
                            goodsInfo.setCouponAmount(findCouponAmount(couponUrl));
                        } else {
                            eStep423 = caller.findElement(By.cssSelector("#clipboard-target"));
                            goodsInfo.setCouponAmount("0");
                        }
                        String code = eStep423.getAttribute("value");
                        log.info("Goods {} -> {}", toJsonString(goodsInfo), code);
                        return code;
                    }
                }
            } catch (Exception e) {
                throw new InvalidOperationException(e);
            }
            log.info("Goods {} not found", goodsInfo.getTitle());
            return null;
        });
    }

    @Override
    public String findCouponAmount(String url) {
        return caller.invokeNew(caller -> {
            By first = By.cssSelector(".coupons-price");
            caller.navigateUrl(url, first);
            return caller.findElement(first).getText();
        });
    }

    @Override
    public GoodsInfo findGoods(String url) {
        return caller.invokeNew(caller -> {
            try {
                GoodsInfo goodsInfo = new GoodsInfo();
                caller.navigateUrl(url, By.cssSelector("body"));
                Thread.sleep(100);
                if (caller.getCurrentUrl().contains(".taobao.com/")) {
                    By first = By.cssSelector(".tb-main-title");
                    goodsInfo.setTitle(caller.findElements(first, first).first().getText());
                    goodsInfo.setSellerNickname(caller.findElement(By.cssSelector(".shop-name-link")).getText());
                } else {
                    By first = By.name("title");
                    goodsInfo.setTitle(caller.findElements(first, first).first().getAttribute("value"));
                    goodsInfo.setSellerId(caller.getAttributeValues(By.name("seller_id"), "value").firstOrDefault());
                    goodsInfo.setSellerNickname(caller.getAttributeValues(By.name("seller_nickname"), "value").firstOrDefault());
                }
                log.info("FindGoods {}\n -> {} -> {}", url, caller.getCurrentUrl(), toJsonString(goodsInfo));
                return goodsInfo;
            } catch (Exception e) {
                log.error("findGoods", e);
                return null;
            }
        });
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
        caller.invokeSelf(caller -> {
            try {
                caller.navigateUrl("https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5spluli");
                String url;
                while ((url = caller.getCurrentUrl()).startsWith("https://www.alimama.com/member/login.htm")) {
                    log.info("please login {}", url);
                    isLogin = false;
                    Thread.sleep(1000);
                }
                if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com/myunion.htm")) {
                    login();
                }
                log.info("login ok...");
                isLogin = true;
            } catch (Exception e) {
                throw new InvalidOperationException(e);
            }
        });
    }

    @SneakyThrows
    @Override
    public void keepLogin() {
        caller.invokeSelf(caller -> {
            String noCache = String.format("&_t=%s", System.currentTimeMillis());
            caller.navigateUrl("https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5spluli" + noCache);
            if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com")) {
                log.info("login keep error...");
            } else {
                log.info("login keep ok...");
            }
        }, true);
    }
}
