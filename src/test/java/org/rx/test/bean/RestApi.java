package org.rx.test.bean;

import org.rx.util.RestMethod;

public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@org.rx.util.RestParam("a") int a, @org.rx.util.RestParam("b") int b);

    String login(@org.rx.util.RestParam("userId") String uid, @org.rx.util.RestParam("pwd") String pwd);

    @RestMethod("/add24")
    RestResult add2(RestParam param);
}
