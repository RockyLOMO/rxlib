package org.rx.test.bean;

import org.rx.common.InvalidOperationException;

public class UserCodeInstance implements UserCode {
    @Override
    public int add(int a, int b) {
        return a + b;
    }

    @Override
    public void testError() {
        throw new InvalidOperationException("testError");
    }
}
