package org.rx.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.rx.util.pinyin.Pinyin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
public class TestUtil {
    @Test
    public void pinyin() {
        String l = "小王，abc";
        Assertions.assertEquals(6, l.length());
        l = "小范";
        String pyn = Pinyin.toPinyin(l, "_");
        Assertions.assertEquals("XIAO_FAN", pyn);

        String subject = "什么小范小范，吃饭啦！,好的";
        String pySubject = Pinyin.toPinyin(subject, "_");
        Assertions.assertEquals("SHEN_ME_XIAO_FAN_XIAO_FAN_，_CHI_FAN_LA_！_,_HAO_DE", pySubject);

        String n = "小范", n1 = "XIAO_FAN";
        Assertions.assertNull(getSubject("今天天气如何", n, n1));
        Assertions.assertEquals("，今天天气如何", getSubject("小范，今天天气如何", n, n1));
        Assertions.assertEquals("，今天天气如何", getSubject("小饭，今天天气如何", n, n1));
        Assertions.assertEquals("，今天天气如何", getSubject("总之呐，小范，今天天气如何", n, n1));
        Assertions.assertEquals("，今天天气如何", getSubject("总之呐，小饭，今天天气如何", n, n1));
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
    @SuppressWarnings("ConstantConditions")
    public void email() {
        String username = null;
        String password = null;
        try {
            List<String> lines = Files.readAllLines(Paths.get("D:\\projs_r\\smtp.txt"), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("Usr:")) {
                    username = line.substring(4).trim();
                } else if (line.startsWith("Pwd:")) {
                    password = line.substring(4).trim();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("smtp.txt not found", e);
        }
        System.setProperty("app.smtp.timeoutMillis", "5000");
        Helper.sendEmail("hw", username, password);
    }
}
