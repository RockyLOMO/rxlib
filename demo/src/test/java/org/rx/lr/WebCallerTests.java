package org.rx.lr;

import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.fl.util.WebCaller;

public class WebCallerTests {
    @SneakyThrows
    @Test
    public void testTab() {
        WebCaller caller = new WebCaller();
        String currentHandle = caller.getCurrentHandle();
        System.out.println(currentHandle);

        String handle = caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.switchTab(handle);
        System.out.println("switch");
        Thread.sleep(2000);

        caller.closeTab(handle);
        System.out.println("close");
    }
}
