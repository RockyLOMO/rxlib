package org.rx.fl.service;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.KeyboardUtils;
import com.sun.jna.platform.WindowUtils;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.win32.StdCallLibrary;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WxBot {
    // kernel32.dll uses the __stdcall calling convention (check the function
// declaration for "WINAPI" or "PASCAL"), so extend StdCallLibrary
// Most C libraries will just extend com.sun.jna.Library,
    public interface Kernel322 extends StdCallLibrary {
        // Method declarations, constant and structure definitions go here
    }
    public WxBot() {

    }
}
