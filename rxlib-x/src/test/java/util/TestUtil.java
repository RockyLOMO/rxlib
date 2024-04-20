package util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.util.Helper;
import org.rx.util.pinyin.Pinyin;

@Slf4j
public class TestUtil {
    @Test
    public void pinyin() {
        String l = "小王，abc";
        System.out.println(l.length());
        l = "小范";
        String pyn = Pinyin.toPinyin(l, "_");
        System.out.println(pyn);

        String subject = "什么小范小范，吃饭啦！,好的";
        String pySubject = Pinyin.toPinyin(subject, "_");
        System.out.println(pySubject);

        String n = "小范", n1 = "XIAO_FAN";
        System.out.println(getSubject("今天天气如何", n, n1));
        System.out.println(getSubject("小范，今天天气如何", n, n1));
        System.out.println(getSubject("小饭，今天天气如何", n, n1));
        System.out.println(getSubject("总之呐，小范，今天天气如何", n, n1));
        System.out.println(getSubject("总之呐，小饭，今天天气如何", n, n1));
    }

    String getSubject(String line, String name, String pinyinName) {
        if (line.length() <= name.length()) {
            return null;
        }
//        line = new StringBuilder(line).replace("，", "")
//                .replace(",", "")
//                .toString();
        String s = "_";
        String pinyinLine = Pinyin.toPinyin(line, s);
        if (pinyinLine.startsWith(pinyinName)) {
            return line.substring(name.length()).trim();
        }
        int i = pinyinLine.indexOf(s + pinyinName);
        if (i == -1) {
            return null;
        }
        int c = 1;
        for (int j = 0; j < i; j++) {
            if (pinyinLine.charAt(j) == s.charAt(0)) {
                c++;
            }
        }
        log.info("{} -> @{} - {}", pinyinLine, i, c);
        return line.substring(c + name.length()).trim();
    }

    @Test
    public void email() {
        Helper.sendEmail("hw", "abc", "rockywong.chn@qq.com");
    }
}
