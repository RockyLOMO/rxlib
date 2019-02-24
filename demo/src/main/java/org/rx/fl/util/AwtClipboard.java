package org.rx.fl.util;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.InvalidOperationException;
import org.rx.util.ManualResetEvent;
import org.rx.util.function.Func;

import java.awt.*;
import java.awt.datatransfer.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.common.Contract.require;

@Slf4j
public class AwtClipboard implements ClipboardOwner {
    private final Clipboard clipboard;
    private final ReentrantLock locker;
    private ManualResetEvent waiter;

    public AwtClipboard() {
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        locker = new ReentrantLock(true);
        waiter = new ManualResetEvent();
        listen();
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
            clipboard.setContents(new StringSelection(text), this);
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
        log.info("waitSetComplete wait @ {}", DateTime.now().toString());
        waiter.waitOne(500);
        log.info("waitSetComplete ok @ {}", DateTime.now().toString());
        Thread.sleep(20);
        waiter.reset();
    }

    private void listen() {
        try {
            clipboard.setContents(clipboard.getContents(null), this);
        } catch (Exception e) {
            log.warn("listen", e);
        }
    }

    @SneakyThrows
    @Override
    public void lostOwnership(Clipboard clipboard, Transferable contents) {
        log.info("lostOwnership and set waiter");
        try {
            Thread.sleep(3);
            listen();
        } catch (Exception e) {
            log.warn("lostOwnership", e);
        }
        waiter.set();
    }
}
