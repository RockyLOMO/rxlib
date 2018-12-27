package org.rx.fl.service;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.rx.Logger;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.task.TaskStartup;
import org.rx.fl.util.WebCaller;

import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.rx.Contract.toJsonString;

@Slf4j
public class TbMedia implements Media {
    @Getter
    private boolean isLogin;
    private WebCaller caller;

    public TbMedia() {
        caller = new WebCaller();
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
        String url = String.format("https://pub.alimama.com/promo/search/index.htm?q=%s", URLEncoder.encode(goodsInfo.getTitle(), "utf-8"));
        System.out.println(url);
        By first = By.cssSelector(".box-btn-left");
        caller.navigateUrl(url, first);
        List<WebElement> bEle = caller.findElements(first).toList();
        List<WebElement> cEle = caller.findElements(By.cssSelector("a[vclick-ignore]")).skip(1).toList();
//            Logger.info("btnSize: %s\tclickSize:%s", bEle.size(), cEle.size());
        for (int i = 0; i < cEle.size(); i++) {
            WebElement element = cEle.get(i);
//                System.out.println(goodsInfo.getSellerNickname().trim() + "\n" + element.getText().trim());
            if (goodsInfo.getSellerNickname().trim().equals(element.getText().trim())) {
                bEle.get(i).click();
                System.out.println("step1");

                By waiter = By.cssSelector("button[mx-click=submit]");
                caller.findElements(waiter, waiter).first().click();
                System.out.println("step2");

                waiter = By.cssSelector("li[mx-click='tab(4)']");
                caller.findElements(waiter, waiter).first().click();
                System.out.println("step3");

                return caller.findElements(By.cssSelector("#clipboard-target")).first().getAttribute("value");
            }
        }
        Logger.info("can not find %s", goodsInfo.getTitle());
        return null;
    }

    @Override
    public GoodsInfo findGoods(String url) {
        GoodsInfo goodsInfo = new GoodsInfo();
        try (WebCaller caller = new WebCaller()) {
            By first = By.name("title");
            caller.navigateUrl(url, first);
            goodsInfo.setTitle(caller.getAttributeValues(first, "value").firstOrDefault());
            goodsInfo.setSellerId(caller.getAttributeValues(By.name("seller_id"), "value").firstOrDefault());
            goodsInfo.setSellerNickname(caller.getAttributeValues(By.name("seller_nickname"), "value").firstOrDefault());
        }
        System.out.println(toJsonString(goodsInfo));
        return goodsInfo;
    }

    @SneakyThrows
    public void login() {
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
    }
}
