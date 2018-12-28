package org.rx.fl.service;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.rx.InvalidOperationException;
import org.rx.Logger;
import org.rx.NQuery;
import org.rx.fl.model.GoodsInfo;
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

    public TbMedia() {
        caller = new WebCaller();
        AsyncTask.TaskFactory.
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
            String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s", URLEncoder.encode(goodsInfo.getTitle(), "utf-8"));
            Logger.debug("findAdv step1 %s", url);
            By first = By.cssSelector(".box-btn-left");
            caller.navigateUrl(url, first);
            List<WebElement> eBtns = caller.findElements(first).toList();
            List<WebElement> eSellers = caller.findElements(By.cssSelector("a[vclick-ignore]")).skip(1).toList();
            List<WebElement> eMoneys = caller.findElements(By.cssSelector(".number-16")).toList();
            Logger.debug("findAdv step2 btnSize: %s\tsellerEles: %s\tmoneyEles: %s", eBtns.size(), eSellers.size(), eMoneys.size());
            for (int i = 0; i < eSellers.size(); i++) {
                WebElement eSeller = eSellers.get(i);
                String sellerName = goodsInfo.getSellerNickname().trim();
                Logger.debug("findAdv step3 %s == %s", sellerName, eSeller.getText());
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
                    Logger.debug("findAdv step4-1 ok");

                    By waiter = By.cssSelector("button[mx-click=submit]");
                    WebElement btn42 = caller.findElements(waiter, waiter).first();
                    Thread.sleep(1000);
                    btn42.click();
//                caller.executeScript("$('button[mx-click=submit]').click();");
                    Logger.debug("findAdv step4-2 ok");

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
                    Logger.debug("findAdv step4-3 ok");

                    eStep423 = caller.findElement(step423, false);
                    if (eStep423 != null) {
                        goodsInfo.setCouponAmount(findCouponAmount(couponUrl));
                    } else {
                        eStep423 = caller.findElement(By.cssSelector("#clipboard-target"));
                        goodsInfo.setCouponAmount("0");
                    }
                    String code = eStep423.getAttribute("value");
                    Logger.info("Goods %s -> %s", toJsonString(goodsInfo), code);
                    return code;
                }
            }
            Logger.info("Goods %s not found", goodsInfo.getTitle());
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
            GoodsInfo goodsInfo = new GoodsInfo();
            caller.navigateUrl(url, By.cssSelector("body"));
            if (caller.getCurrentUrl().contains(".tmall.com/")) {
                By first = By.name("title");
                goodsInfo.setTitle(caller.findElements(first, first).first().getAttribute("value"));
                goodsInfo.setSellerId(caller.getAttributeValues(By.name("seller_id"), "value").firstOrDefault());
                goodsInfo.setSellerNickname(caller.getAttributeValues(By.name("seller_nickname"), "value").firstOrDefault());
            } else {
                By first = By.cssSelector(".tb-main-title");
                goodsInfo.setTitle(caller.findElements(first, first).first().getText());
                goodsInfo.setSellerNickname(caller.findElement(By.cssSelector(".shop-name-link")).getText());
            }
            Logger.info("FindGoods %s\n -> %s -> %s", url, caller.getCurrentUrl(), toJsonString(goodsInfo));
            return goodsInfo;
        });
    }

    @Override
    public String findLink(String content) {
        int s = content.indexOf("http"), e;
        if (s == -1) {
            throw new InvalidOperationException("Start flag not found %s", content);
        }
        e = content.indexOf(" ", s);
        if (e == -1) {
            throw new InvalidOperationException("End flag not found %s", content);
        }
        return content.substring(s, e);
    }

    @SneakyThrows
    public void login() {
        caller.invokeSelf(caller -> {
            caller.navigateUrl("https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5spluli");
            String url;
            while ((url = caller.getCurrentUrl()).startsWith("https://www.alimama.com/member/login.htm")) {
                Logger.info("please login %s", url);
                isLogin = false;
                Thread.sleep(1000);
            }
            if (!caller.getCurrentUrl().startsWith("https://pub.alimama.com/myunion.htm")) {
                login();
            }
            Logger.info("login ok...");
            isLogin = true;
        });
    }
}
