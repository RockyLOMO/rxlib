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
    private final PersonBean user;
    @Setter
    private int flag;
    @Setter
    private List<String> statefulList = new ArrayList<>();
}
