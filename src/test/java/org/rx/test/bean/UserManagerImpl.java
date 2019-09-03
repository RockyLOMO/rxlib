package org.rx.test.bean;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.core.EventArgs;
import org.rx.core.InvalidOperationException;

import java.util.List;
import java.util.function.BiConsumer;

public class UserManagerImpl implements UserManager {
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class MgrEventArgs extends EventArgs {
        private List<String> resultList;
    }

    public volatile BiConsumer<UserManager, MgrEventArgs> onAdd;

    @Override
    public boolean dynamicAttach() {
        return true;
    }

    @Override
    public void addUser() {
        MgrEventArgs e = new MgrEventArgs();
        raiseEvent(onAdd, e);
        System.out.println("MgrEventArgs: " + JSON.toJSONString(e.getResultList()));
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
