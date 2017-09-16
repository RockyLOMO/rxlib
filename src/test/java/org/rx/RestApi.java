package org.rx;

import org.rx.util.RestParam;
import org.rx.util.RestMethod;

public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@RestParam("a") int a, @RestParam("b") int b);

    String login(@RestParam("userId") String uid, @RestParam("pwd") String pwd);

    @RestMethod("/add24")
    ObjectResult add2(ObjectParam param);
}
