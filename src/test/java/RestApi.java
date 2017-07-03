import org.rx.util.RestMethod;
import org.rx.util.RestParameter;

/**
 * Created by za-wangxiaoming on 2017/7/3.
 */
public interface RestApi {
    void m1();

    void m2(int a);

    @RestMethod(isFormParam = true)
    String m3(@RestParameter(name = "ss") int b, String c);

    String m4(int z);
}
