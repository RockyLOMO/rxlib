package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;
import org.rx.fl.util.WebCaller;
import org.rx.util.AsyncTask;

import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.Contract.toJsonString;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class TbMedia implements Media {
    private static final String[] keepLoginUrl = {"https://pub.alimama.com/myunion.htm?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/detail/taoke?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/myunion.htm#!/report/zone/zone_self?spm=a219t.7664554.a214tr8.2.a0e435d9ahtooV",
            "https://pub.alimama.com/manage/overview/index.htm?spm=a219t.7900221/1.1998910419.dbb742793.6f2075a54ffHxF",
            "https://pub.alimama.com/manage/selection/list.htm?spm=a219t.7900221/1.1998910419.d3d9c63c9.6f2075a54ffHxF"};
    private static final int sleepMillis = 100;

    @Getter
    private boolean isLogin;
    private WebCaller caller;

    @Override
    public MediaType getType() {
        return MediaType.Taobao;
    }

    public TbMedia() {
        caller = new WebCaller();
        TaskFactory.schedule(() -> keepLogin(), 2 * 1000, 35 * 1000, "TbMedia");
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
                String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s&_t=%s", URLEncoder.encode(goodsInfo.getTitle(), "utf-8"), System.currentTimeMillis());
                log.info("findAdv step1 {}", url);
                By first = By.cssSelector(".box-btn-left");
                App.retry(p -> {
                    caller.navigateUrl(url, first);
                    return null;
                }, null, 2);
                Thread.sleep(sleepMillis);
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

                        try {
                            eBtns.get(i).click();
                        } catch (WebDriverException e) {
                            log.info("findAdv step4-1 click {}", e.getMessage());
                            caller.executeScript("$('.box-btn-left:eq(" + i + ")').click();");
                            Thread.sleep(sleepMillis);
                        }
                        log.info("findAdv step4-1 ok");

                        By waiter = By.cssSelector("button[mx-click=submit]");
                        WebElement btn42 = caller.findElements(waiter, waiter).first();
                        Thread.sleep(sleepMillis);
                        try {
                            btn42.click();
                        } catch (WebDriverException e) {
                            log.info("findAdv step4-2 click %s", e.getMessage());
                            caller.executeScript("$('button[mx-click=submit]').click();");
                            Thread.sleep(sleepMillis);
                        }
                        log.info("findAdv step4-2 ok");

                        By hybridCodeBy = By.cssSelector("#clipboard-target,#clipboard-target-2");
                        caller.findElements(hybridCodeBy, hybridCodeBy).first();
                        Thread.sleep(sleepMillis);
                        goodsInfo.setCouponAmount("0");
                        Future<String> future = null;
                        WebElement code2 = caller.findElement(By.cssSelector("#clipboard-target-2"), false);
                        if (code2 != null) {
                            future = TaskFactory.run(() -> {
                                String couponUrl = code2.getAttribute("value");
                                if (Strings.isNullOrEmpty(couponUrl)) {
                                    log.info("findAdv step4-2-2 couponUrl fail and retry");
                                    couponUrl = (String) caller.executeScript("return $('#clipboard-target-2').val();");
                                }
                                if (Strings.isNullOrEmpty(couponUrl)) {
                                    log.info("findAdv step4-2-2 couponUrl is null -> {}", toJsonString(goodsInfo));
                                    return "0";
                                }
                                return findCouponAmount(couponUrl);
                            });
                        }

                        waiter = By.cssSelector("li[mx-click='tab(4)']");
                        WebElement btn43 = caller.findElements(waiter, waiter).first();
                        Thread.sleep(sleepMillis);
                        btn43.click();
                        log.info("findAdv step4-3 ok");

                        WebElement codeX = caller.findElement(hybridCodeBy);
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
                By hybridSelector = By.cssSelector(".tb-main-title,input[name=title]");
                caller.navigateUrl(url, hybridSelector);
                Thread.sleep(sleepMillis);
                WebElement hybridElement = caller.findElement(hybridSelector);
                if (caller.getCurrentUrl().contains(".taobao.com/")) {
                    goodsInfo.setTitle(hybridElement.getText());
                    goodsInfo.setSellerNickname(caller.findElement(By.cssSelector(".shop-name-link")).getText());
                } else {
                    goodsInfo.setTitle(hybridElement.getAttribute("value"));
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
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            try {
                caller.navigateUrl("https://pub.alimama.com/myunion.htm");
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
            int i = ThreadLocalRandom.current().nextInt(0, keepLoginUrl.length);
            caller.navigateUrl(keepLoginUrl[i] + noCache);
            if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com")) {
                log.info("login keep error...");
                isLogin = false;
            } else {
                log.info("login keep ok...");
                isLogin = true;
            }
        }, true);
    }
}
