package org.rx.fl.service.media;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Point;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.WebBrowser;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.WebBrowser.BodySelector;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class PddMedia implements Media {
    private static final String loginUrl = "https://jinbao.pinduoduo.com/promotion/single-promotion";
    private static final String[] keepLoginUrl = {"https://jinbao.pinduoduo.com/data/data-analysis",
            "https://jinbao.pinduoduo.com/data/data-analysis",
            "https://jinbao.pinduoduo.com/data/data-analysis"};

    @Getter
    private volatile boolean isLogin;
    private WebBrowser caller;

    @Override
    public MediaType getType() {
        return MediaType.Pinduoduo;
    }

    public PddMedia(MediaConfig config) {
        require(config);

//        caller = new WebBrowser();
//        TaskFactory.schedule(() -> keepLogin(true), 2 * 1000, config.getPdd().getKeepLoginSeconds() * 1000, this.getType().name());
    }

    @Override
    public String findLink(String content) {
        return null;
    }

    @Override
    public GoodsInfo findGoods(String url) {
        return null;
    }

    @Override
    public String findCouponAmount(String url) {
        return null;
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        return null;
    }

    @Override
    public FindAdvResult getHighCommissionAdv(String goodsName) {
        return null;
    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        return null;
    }

    @Override
    public void login() {
        if (isLogin) {
            return;
        }

        caller.invokeSelf(caller -> {
            caller.navigateUrl(keepLoginUrl[0], BodySelector);
            while (caller.getCurrentUrl().equals(loginUrl)) {
                caller.elementClick(".login-btn");
                delay(800);
                caller.elementClick(".btn-link");
                delay(800);
                caller.elementPress("#mobile", "17091916400");
                delay(1000);
                caller.elementPress("input[type=password]", new String(App.convertFromBase64String("ZmFubGkteCY0ZXZlcg==")));
                delay(1000);
                caller.elementClick(".pdd-btn");
//                Point point = element.getRect().getPoint();
//                int y = driver.manage().window().getSize().height / 2 + 85;
////                    System.out.println(point.x + "," + point.y + "," + y);
////                    AwtBot.getBot().mouseLeftClick(point.x + 10, y);
                delay(2000);
            }
            log.info("login ok...");
            isLogin = true;
        });
    }

    @SneakyThrows
    private void keepLogin(boolean skipIfBusy) {
        caller.invokeSelf(caller -> {
            String noCache = String.format("?_t=%s", System.currentTimeMillis());
            int i = ThreadLocalRandom.current().nextInt(0, keepLoginUrl.length);
            caller.navigateUrl(keepLoginUrl[i] + noCache, BodySelector);
            if (caller.getCurrentUrl().equals(loginUrl)) {
                log.info("login expired...");
                isLogin = false;
                login();
            } else {
                log.info("login ok...");
                isLogin = true;
            }
        }, skipIfBusy);
    }
}
