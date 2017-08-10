import com.alibaba.fastjson.JSON;
import lombok.Data;
import org.rx.util.BeanMapper;
import org.rx.util.RestClient;

import java.io.File;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Created by za-wangxiaoming on 2017/6/28.
 */
public class test {
    private static Random rnd = new Random();

    public static void main(String[] args) throws Exception {
        //        int max = 10;
        //        for (int i = 0; i < 9; i++) {
        //            System.out.println(rnd.nextInt(max) + "-" + new Random().nextInt(max));
        //        }
        //testRest();
        System.out.println(File.pathSeparatorChar + "," + File.separatorChar);
        testMap();
    }

    private static void testRest() {
        String proxy = null;
        //proxy = "127.0.0.1:8888";
        RestApi client = RestClient.create(RestApi.class, "http://localhost:8081", proxy, true);
        System.out.println(client.getClass());
        //        client.test();
        //        client.add(1, 1);
        //        client.login("Rocky", "abc123");
        //        ObjectParam p = new ObjectParam();
        //        p.setA(12);
        //        p.setB(12);
        //        client.add2(p);
    }

    @Data
    public static class SourceBean {
        private String  name;
        private int     age;
        private boolean sex;
        private Long    money;
    }

    @Data
    public static class TargetBean {
        private String     name;
        private String     age;
        private int        luckyNum;
        private BigDecimal money;
        private String     info;
    }

    private static void testMap() {
        BeanMapper mapper = new BeanMapper();
        mapper.setConfig(SourceBean.class, TargetBean.class, targetName -> {
switch (targetName){
    case "setInfo":

        break;
}
        }, "setLuckyNum");

        SourceBean f = new SourceBean();
        f.setName("HW");
        f.setAge(100);
        f.setMoney(200L);
        TargetBean t = mapper.map(f, TargetBean.class);
        System.out.println(t);
    }
}
