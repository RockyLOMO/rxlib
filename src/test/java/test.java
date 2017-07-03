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
        //testRest();
    }

    private static void testRest() {
        RestApi client = RestClient.create(RestApi.class, "http://www.baidu.com/api/v2", "127.0.0.1:8888");
        client.m1();
        client.m2(1);
        client.m3(1, "abc");
        client.m4(7);
    }
}
