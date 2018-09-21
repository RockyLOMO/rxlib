package org.rx.test.bean;

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
