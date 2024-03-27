package org.rx.util;

import lombok.NonNull;
import org.rx.core.Strings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class UrlGenerator implements Iterable<String> {
    private final List<String> urls = new ArrayList<>();

    @Override
    public Iterator<String> iterator() {
        return urls.iterator();
    }

    public UrlGenerator(@NonNull String urlExpression) {
        int s = urlExpression.indexOf("["), e;
        if (s == -1 || (e = urlExpression.indexOf("]", s)) == -1) {
            urls.add(urlExpression);
            return;
        }
        String rangeString = urlExpression.substring(s, e + 1);
        String[] ranges = Strings.split(rangeString.substring(1, rangeString.length() - 1), "-", 2);
        int f = Integer.parseInt(ranges[0]), t = Integer.parseInt(ranges[1]);
        for (; f <= t; f++) {
            urls.add(urlExpression.replace(rangeString, String.valueOf(f)));
        }
    }
}
