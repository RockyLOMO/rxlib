package org.rx.test.bean;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.InvalidException;

import java.util.function.BiConsumer;

import static org.rx.core.App.toJsonString;

@Slf4j
public class UserManagerImpl implements UserManager {
    public volatile BiConsumer<UserManager, UserEventArgs> onCreate;

    @Override
    public void create(PersonBean person) {
        UserEventArgs e = new UserEventArgs(person);
        raiseEvent(onCreate, e);
        if (e.isCancel()) {
            log.info("create canceled");
            return;
        }
        log.info("create ok & statefulList={}", toJsonString(e.getStatefulList()));
    }

    @Override
    public int computeInt(int a, int b) {
        return a + b;
    }

    @Override
    public void triggerError() {
        throw new InvalidException("自定义异常描述");
    }
}
