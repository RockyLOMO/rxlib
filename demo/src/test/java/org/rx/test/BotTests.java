package org.rx.test;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.media.JdLoginBot;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.ImageUtil;
import weixin.popular.api.TokenAPI;
import weixin.popular.bean.token.Token;

import java.awt.*;
import java.util.List;
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
        String AppID = "wxb0d765c458047d1d";
        String AppSecret = "06f554fea2bd6f3f234480dbdc6ca6ed";
        Token token = TokenAPI.token(AppID, AppSecret);
        String accessToken = token.getAccess_token();
    }
}
