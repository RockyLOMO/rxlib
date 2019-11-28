package org.rx.test.bean;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.InvalidOperationException;

import java.util.function.BiConsumer;

@Slf4j
public class UserManagerImpl implements UserManager {
    public volatile BiConsumer<UserManager, UserEventArgs> onAddUser;

//    @Override
//    public FlagsEnum<EventFlags> eventFlags() {
//        return EventFlags.DynamicAttach.add();
//    }

    @Override
    public void addUser(PersonInfo person) {
        UserEventArgs e = new UserEventArgs(person);
        raiseEvent(onAddUser, e);
        if (e.isCancel()) {
            log.info("addUser canceled");
            return;
        }
        log.info("UserEventArgs.resultList {}", JSON.toJSONString(e.getResultList()));
        log.info("addUser ok");
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
