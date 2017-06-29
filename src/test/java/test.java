import java.util.Random;

/**
 * Created by za-wangxiaoming on 2017/6/28.
 */
public class test {
    private static Random rnd = new Random();

    public static void main(String[] args) {
        int max = 10;
        for (int i = 0; i < 9; i++) {
            System.out.println(rnd.nextInt(max) + "-" + new Random().nextInt(max));
        }
    }
}
