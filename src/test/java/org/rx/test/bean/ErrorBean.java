package org.rx.test.bean;

/**
 * Created by IntelliJ IDEA.
 * User: za-wangxiaoming
 * Date: 2017/12/22
 */
public class ErrorBean {
    private String error;

//    public ErrorBean(int a){
//
//    }

    public String getError() {
        throw new IllegalArgumentException("test");
//        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
