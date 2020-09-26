package org.rx.test.bean;

import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.core.exception.InvalidException;

import java.util.function.BiConsumer;

import static org.rx.core.Contract.toJsonString;

@Slf4j
public class UserManagerImpl implements UserManager {
    public volatile BiConsumer<UserManager, UserEventArgs> onAddUser;

    @Override
    public FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.flags();
    }

    @Override
    public void addUser(PersonBean person) {
        UserEventArgs e = new UserEventArgs(person);
        raiseEvent(onAddUser, e);
        if (e.isCancel()) {
            log.info("addUser canceled");
            return;
        }
        log.info("UserEventArgs.resultList {}", toJsonString(e.getResultList()));
        log.info("addUser ok");
    }

    @Override
    public int computeInt(int a, int b) {
        return a + b;
    }

    @Override
    public void testError() {
        throw new InvalidException("testError");
    }
}
