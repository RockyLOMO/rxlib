package org.rx;

import org.rx.ObjectParam;
import org.rx.ObjectResult;
import org.rx.util.RestMethod;
import org.rx.util.RestParam;

public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@RestParam("a") int a, @RestParam("b") int b);

    String login(@RestParam("userId") String uid, @RestParam("pwd") String pwd);

    @RestMethod("/add24")
    ObjectResult add2(ObjectParam param);
}
