package org.rx.fl.service;

import com.alibaba.fastjson.JSONArray;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.rx.Logger;
import org.rx.NQuery;
import org.rx.fl.model.BotUser;
import org.rx.fl.util.WebCaller;
import org.rx.util.AsyncTask;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Slf4j
public class WxBot implements Bot {
    @Getter
    private volatile boolean isLogin;
    private WebCaller caller;

    public WxBot() {
        caller = new WebCaller();
        AsyncTask.TaskFactory.schedule(() -> messageReceived(), 1000);
    }

    public void messageReceived() {
        caller.invokeSelf(caller -> {
            String result = (String) caller.executeScript("        $(\".web_wechat_reddot_middle\").each(function (i, o) {\n" +
                    "            var me = $(o), chatItem = me.parent().parent();\n" +
                    "            users.push({\n" +
                    "                openId: chatItem.attr(\"data-username\"),\n" +
                    "                nickname: chatItem.find(\".nickname_text\").text(),\n" +
                    "                unread: me.text()\n" +
                    "            });\n" +
                    "        });\n" +
                    "        return users.toString();");
            List<BotUser> users = JSONArray.parseArray(result, BotUser.class);
            if (users.isEmpty()) {
                return;
            }

            for (WebElement element : caller.findElements(By.cssSelector(".chat_item[rx=1]"))) {
                element.click();


            }
        });
    }

    @SneakyThrows
    @Override
    public void login() {
        caller.invokeSelf(caller -> {
            caller.navigateUrl("https://wx.qq.com");
            By first = By.cssSelector(".chat_item");
            while (!caller.findElements(first).any()) {
                Logger.info("please login...");
                isLogin = false;
                Thread.sleep(1000);
            }
            Logger.info("login ok...");
            isLogin = true;
        });
    }
}
