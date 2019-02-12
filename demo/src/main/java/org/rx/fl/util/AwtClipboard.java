package org.rx.fl.util;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.InvalidOperationException;
import org.rx.util.ManualResetEvent;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.awt.*;
import java.awt.datatransfer.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.common.Contract.require;

@Slf4j
public class AwtClipboard
//        implements ClipboardOwner
{
    private static final int setDelay = 90;  //多10
    private final Clipboard clipboard;
    private final ReentrantLock locker;
//    private ManualResetEvent waiter;

    public AwtClipboard() {
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        locker = new ReentrantLock(true);
//        waiter = new ManualResetEvent();
//        listen();
    }

    public <T> T lock(Func<T> action) {
        require(action);

        locker.lock();
        try {
            return action.invoke();
        } finally {
            locker.unlock();
        }
    }

    public String getString() {
        return lock(() -> {
            Transferable t = clipboard.getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return null;
            }
            try {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            } catch (Exception e) {
                throw new InvalidOperationException(e);
            }
        });
    }

    @SneakyThrows
    public void setContent(String text) {
        lock(() -> {
            clipboard.setContents(new StringSelection(text), null);
//            Thread.sleep(setDelay);
            return null;
        });
    }

//    public void setContent(Image image) {
//        synchronized (clipboard) {
//            clipboard.setContents(new Transferable() {
//                @Override
//                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
//                    if (!isDataFlavorSupported(flavor)) {
//                        throw new UnsupportedFlavorException(flavor);
//                    }
//                    return image;
//                }
//
//                @Override
//                public DataFlavor[] getTransferDataFlavors() {
//                    return new DataFlavor[]{DataFlavor.imageFlavor};
//                }
//
//                @Override
//                public boolean isDataFlavorSupported(DataFlavor flavor) {
//                    return DataFlavor.imageFlavor.equals(flavor);
//                }
//            }, null);
//        }
//    }

    @SneakyThrows
    public void waitSetComplete() {
        Thread.sleep(setDelay);
//        waiter.waitOne(clipboardDelay);
//        log.info("waitSetComplete ok");
//        waiter.reset();
    }

//    private void listen() {
//        clipboard.setContents(clipboard.getContents(null), this);
//    }
//
//    @SneakyThrows
//    @Override
//    public void lostOwnership(Clipboard clipboard, Transferable contents) {
//        log.info("lostOwnership and set waiter");
//        try {
//            Thread.sleep(2);
//            listen();
//        } catch (Exception e) {
//            log.warn("lostOwnership", e);
//        }
//        waiter.set();
//    }
}
