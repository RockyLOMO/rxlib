package org.rx.test.bean;

import org.rx.feign.RestMethod;

public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@org.rx.feign.RestParam("a") int a, @org.rx.feign.RestParam("b") int b);

    String login(@org.rx.feign.RestParam("userId") String uid, @org.rx.feign.RestParam("pwd") String pwd);

    @RestMethod("/add24")
    RestResult add2(RestParam param);
}
