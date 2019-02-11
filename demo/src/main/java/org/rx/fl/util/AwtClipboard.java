package org.rx.fl.util;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.ManualResetEvent;

import java.awt.*;
import java.awt.datatransfer.*;

@Slf4j
public class AwtClipboard
//        implements ClipboardOwner
{
    private static final int clipboardDelay = 80;
    private final Clipboard clipboard;
//    private ManualResetEvent waiter;

    public AwtClipboard() {
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
//        waiter = new ManualResetEvent();
//        listen();
    }

    @SneakyThrows
    public String getString() {
        synchronized (clipboard) {
            Transferable t = clipboard.getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return null;
            }
            return (String) t.getTransferData(DataFlavor.stringFlavor);
        }
    }

    @SneakyThrows
    public void setContent(String text) {
        synchronized (clipboard) {
            clipboard.setContents(new StringSelection(text), null);
        }
        Thread.sleep(clipboardDelay);
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
        Thread.sleep(clipboardDelay);
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
