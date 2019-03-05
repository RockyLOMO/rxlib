package org.rx.test;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.media.JdLoginBot;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.ImageUtil;
import weixin.popular.api.ShorturlAPI;
import weixin.popular.api.TokenAPI;
import weixin.popular.bean.token.Token;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BotTests {
    @SneakyThrows
    @Test
    public void normal() {
        //mHspqTfyNqPw89gBJiXdYd
        System.out.println(App.toShorterUUID(UUID.fromString("c7bc68c9-d6f0-da3b-e3a7-7db62fd0d567")));

//        System.out.println(App.toShorterUUID(UUID.fromString("5483c10a-98d4-63cf-b427-31e26251ed8d")));
//        System.out.println(App.fromShorterUUID("EkCHKMVpEdpnDSqdam384H"));
//        AwtBot bot = AwtBot.getBot();
//        int y = (int) bot.getScreenRectangle().getHeight();
//        bot.clickAndAltF4(220, y - 20);
//        bot.mouseRightClick(220, y - 20);
//        bot.delay(1000);
//        bot.mouseLeftClick(220, y - 64);
//        bot.clickByImage(ImageUtil.getImageFromResource(WxBot.class, "/static/jdKey2.png"));

//        String url = "https://szsupport.weixin.qq.com/cgi-bin/mmsupport-bin/readtemplate?t=w_redirect_taobao&url=https%3A%2F%2Fitem.taobao.com%2Fitem.htm%3Fspm%3Da230r.1.14.164.436b3078xt4nB5%26id%3D14312037600%26ns%3D1%26abbucket%3D1%23detail&lang=zh_CN";
//        HttpUrl httpUrl = HttpUrl.get(url);
//        for (String name : httpUrl.queryParameterNames()) {
//            System.out.println("name:" + name);
//            System.out.println("value:" + httpUrl.queryParameter(name));
//        }
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void wxBot() {
        WxMobileBot bot = new WxMobileBot(500, 2, 1, 1, 20);
        bot.onReceiveMessage(p -> {
            System.out.println(JSON.toJSONString(p));
            return NQuery.of(p).select(msg -> "已收到消息：" + msg).toList();
        });
        bot.start();
        System.out.println("start...");

        Thread.sleep(8000);
        System.out.println("test...");
        bot.getMessageRectangle();
        System.out.println(bot.findScreenPoints(WxMobileBot.KeyImages.Msg2));

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void jdHack() {
        JdLoginBot login = new JdLoginBot(8081);
        Thread.sleep(2000);
        System.out.println(login.produceKey());
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void getScreenPoint() {
        Class owner = AwtBot.class;
        AwtBot bot = AwtBot.getBot();
        List<Point> points = bot.findScreenPoints(ImageUtil.getImageFromResource(owner, "/bot/wxUnread0.png"));
        System.out.println(points);
        points = bot.findScreenPoints(ImageUtil.getImageFromResource(owner, "/bot/wxUnread1.png"));
//        points = bot.findScreenPoints(ImageUtil.loadImage("D:\\1.png"));
        System.out.println(points);
    }

    @Test
    public void wxService() {
        String long_url = "http://f-li.cn?id=wxb0d765c458047d1d";
        String apiUrl = String.format("http://dwz.wailian.work/api.php?from=w&url=%s=&site=sina", App.convertToBase64String(long_url.getBytes()));
        HttpCaller caller = new HttpCaller();
        Map<String, String> map = new HashMap<>();
        map.put("Cookie", "PHPSESSID=63btgsg62gursl7vtu17o96kj6; __51cke__=; td_cookie=3631118679; Hm_lvt_fd97a926d52ef868e2d6a33de0a25470=1550555992,1551777578; Hm_lpvt_fd97a926d52ef868e2d6a33de0a25470=1551777578; __tins__19242943=%7B%22sid%22%3A%201551777577820%2C%20%22vd%22%3A%201%2C%20%22expires%22%3A%201551779377820%7D; __51laig__=3");
        caller.setHeaders(map);
        String responseText = caller.get(apiUrl);
        System.out.println(responseText);
        String shortUrl = JSON.parseObject(responseText).getJSONObject("data").getString("short_url");
        System.out.println(shortUrl);
//        String AppID = "wxb0d765c458047d1d";
//        String AppSecret = "06f554fea2bd6f3f234480dbdc6ca6ed";
//        Token token = TokenAPI.token(AppID, AppSecret);
//        System.out.println(JSON.toJSONString(token));
//        String accessToken = token.getAccess_token();
//        String url = ShorturlAPI.shorturl(accessToken, "http://f-li.cn?id=wxb0d765c458047d1d").getShort_url();
//        System.out.println(url);
    }
}
