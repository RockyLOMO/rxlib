package org.rx.net.support;

public interface IPSearcher {
    IPSearcher DEFAULT = new ComboIPSearcher();

    IPAddress current();

    IPAddress search(String ip);
}
