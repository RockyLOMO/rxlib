import org.rx.util.RestMethod;
import org.rx.util.RestParam;

/**
 * Created by wangxiaoming on 2017/7/3.
 */
public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@RestParam("a") int a, @RestParam("b") int b);

    String login(@RestParam("userId") String uid, @RestParam("pwd") String pwd);

    @RestMethod("/add24")
    ObjectResult add2(ObjectParam param);
}
