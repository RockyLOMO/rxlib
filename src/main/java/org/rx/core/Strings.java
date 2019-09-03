package org.rx.core;

import org.apache.commons.lang3.StringUtils;
import org.rx.annotation.ErrorCode;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.rx.core.Contract.values;

public class Strings extends StringUtils {
    public static final String empty = "";

    public static String randomValue(int maxValue) {
        Integer int2 = maxValue;
        return String.format("%0" + int2.toString().length() + "d", ThreadLocalRandom.current().nextInt(maxValue));
    }

    public static String maskPrivacy(String val) {
        if (isNullOrEmpty(val)) {
            return "";
        }

        val = val.trim();
        int len = val.length(), left, right;
        switch (len) {
            case 11:
                left = 3;
                right = 4;
                break;
            case 18:
                left = 4;
                right = 6;
                break;
            default:
                if (len < 3) {
                    left = 1;
                    right = 0;
                } else {
                    left = right = len / 3;
                }
                break;
        }
        String x = com.google.common.base.Strings.repeat("*", len - left - right);
        return val.substring(0, left) + x + val.substring(left + x.length());
    }

    public static String toTitleCase(String s) {
        return StringUtils.capitalize(s);
    }

    public static String[] split(String str, String delimiter) {
        return split(str, delimiter, null);
    }

    @ErrorCode(value = "lengthError", messageKeys = {"$len"})
    public static String[] split(String str, String delimiter, Integer length) {
        String[] result;
        if (isNullOrEmpty(str)) {
            result = new String[0];
        } else {
            result = str.split(Pattern.quote(delimiter));
        }
        if (length != null && length != result.length) {
            throw new SystemException(values(length), "lengthError");
        }
        return result;
    }

    public static boolean isNullOrEmpty(String input) {
        return input == null || input.length() == 0 || "null".equals(input);
    }

    public static boolean isNullOrWhiteSpace(String input) {
        return isNullOrEmpty(input) || input.trim().length() == 0;
    }
}
