package org.rx.test.bean;

import org.rx.common.EventArgs;
import org.rx.common.InvalidOperationException;

import java.util.function.BiConsumer;

public class UserManagerImpl implements UserManager {
    public volatile BiConsumer<UserManager, EventArgs> onAdd;

    @Override
    public boolean dynamicAttach() {
        return true;
    }

    @Override
    public void addUser() {
        raiseEvent(onAdd, EventArgs.empty);
        System.out.println("call addUser..");
    }

    @Override
    public int computeInt(int a, int b) {
        return a + b;
    }

    @Override
    public void testError() {
        throw new InvalidOperationException("testError");
    }
}
