package org.rx.test.fl;

import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.common.Contract;
import org.rx.fl.repository.model.User;
import org.rx.fl.service.command.CommandManager;
import org.rx.fl.util.DbUtil;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

import static org.rx.common.App.getClassLoader;
import static org.rx.common.App.windowsOS;

//@RunWith(SpringRunner.class)
//@SpringBootTest
public class DbTests {
    @Resource
    CommandManager commandManager;
    @Resource
    DbUtil dbUtil;

    @SneakyThrows
    @Test
    public void hello() {
      String packageDirName = "org.rx.fl.service.command.impl";
        packageDirName = packageDirName.replace('.', '/');
//        String flag = ".class";
//        List<Class> classes = new ArrayList();
//        Consumer<String> loadFunc = (n) -> {
//            Class type = loadClass(n, initClass, false);
//            if (type != null) {
//                classes.add(type);
//            }
//        };
        Enumeration dirs = CommandManager.class.getClassLoader().getResources(packageDirName);
        while (dirs.hasMoreElements()){

            System.out.println(dirs.nextElement());
        }


       // commandManager.handleMessage("4c3d0808-2480-3a17-837d-e6fe068c1a0a","【卡通羊羔绒宝宝绒四件套秋冬立体贴布绣花儿童被子双面绒恐龙被套】https://m.tb.cn/h.3ruLcuh 点击链接，再选择浏览器咑閞；或復·制这段描述￥P1ScbKlP0Lp￥后到淘♂寳♀[来自超级会员的分享]");

//        User u = new User();
////        Field field = User.class.getDeclaredField("id");
////        field.setAccessible(true);
////        field.set(u, "abc");
////        System.out.println(u.getId());
//        u.setOpenId("abc");
//        dbUtil.save(u);
//        System.in.read();
    }
}
