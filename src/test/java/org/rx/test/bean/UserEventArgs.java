package org.rx.test.bean;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.EventArgs;

import java.util.ArrayList;
import java.util.List;

@Getter
@RequiredArgsConstructor
public class UserEventArgs extends EventArgs {
    private final PersonInfo user;
    @Setter
    private int flag;
    private final List<String> resultList = new ArrayList<>();
}
