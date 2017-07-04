import org.rx.util.RestClient;

import java.util.Random;

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
        testRest();
    }

    private static void testRest() {
        String proxy = null;
        //proxy = "127.0.0.1:8888";
        RestApi client = RestClient.create(RestApi.class, "http://localhost:8081", proxy);
        client.test();
        client.add(1, 1);
        client.login("Rocky", "abc123");
        ObjectParam p = new ObjectParam();
        p.setA(12);
        p.setB(12);
        client.add2(p);
    }
}
