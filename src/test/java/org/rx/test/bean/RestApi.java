package org.rx.test.bean;

import org.rx.socks.http.RestMethod;

public interface RestApi {
    @RestMethod(method = "GET")
    void test();

    int add(@org.rx.socks.http.RestParam("a") int a, @org.rx.socks.http.RestParam("b") int b);

    String login(@org.rx.socks.http.RestParam("userId") String uid, @org.rx.socks.http.RestParam("pwd") String pwd);

    @RestMethod("/add24")
    RestResult add2(RestParam param);
}
