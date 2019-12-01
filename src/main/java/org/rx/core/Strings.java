package org.rx.core;

import org.apache.commons.lang3.StringUtils;
import org.rx.annotation.ErrorCode;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.rx.core.Contract.values;

public class Strings extends StringUtils {
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
        String x = Strings.repeat("*", len - left - right);
        return val.substring(0, left) + x + val.substring(left + x.length());
    }

    public static String toTitleCase(String s) {
        return StringUtils.capitalize(s);
    }

    public static String[] split(String str, String delimiter) {
        return split(str, delimiter, -1);
    }

    @ErrorCode(value = "lengthError", messageKeys = {"$len"})
    public static String[] split(String str, String delimiter, int fixedLength) {
        String[] result;
        if (isNullOrEmpty(str)) {
            result = new String[0];
        } else {
            result = str.split(Pattern.quote(delimiter));
        }
        if (fixedLength > -1 && fixedLength != result.length) {
            throw new SystemException(values(fixedLength), "lengthError");
        }
        return result;
    }

    public static boolean isNullOrEmpty(String input) {
        return input == null || input.length() == 0 || "null".equals(input);
    }

    public static boolean isNullOrWhiteSpace(String input) {
        return isNullOrEmpty(input) || input.trim().length() == 0;
    }

    //region Nested
    public interface RegularExp {
        /**
         * 验证email地址
         */
        String Email = "^[\\w-]+(\\.[\\w-]+)*@[\\w-]+(\\.[\\w-]+)+$";
        /**
         * 验证手机号码
         */
        String Mobile = "^0{0,1}1[3|5|7|8]\\d{9}$";
        /**
         * 验证电话号码
         */
        String Telephone = "(\\d+-)?(\\d{4}-?\\d{7}|\\d{3}-?\\d{8}|^\\d{7,8})(-\\d+)?";
        /**
         * 验证日期（YYYY-MM-DD）
         */
        String Date = "^((((1[6-9]|[2-9]\\d)\\d{2})-(0?[13578]|1[02])-(0?[1-9]|[12]\\d|3[01]))|(((1[6-9]|[2-9]\\d)\\d{2})-(0?[13456789]|1[012])-(0?[1-9]|[12]\\d|30))|(((1[6-9]|[2-9]\\d)\\d{2})-0?2-(0?[1-9]|1\\d|2[0-8]))|(((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00))-0?2-29-))$";
        /**
         * 验证日期和时间（YYYY-MM-DD HH:MM:SS）
         */
        String DateTime = "^((((1[6-9]|[2-9]\\d)\\d{2})-(0?[13578]|1[02])-(0?[1-9]|[12]\\d|3[01]))|(((1[6-9]|[2-9]\\d)\\d{2})-(0?[13456789]|1[012])-(0?[1-9]|[12]\\d|30))|(((1[6-9]|[2-9]\\d)\\d{2})-0?2-(0?[1-9]|1\\d|2[0-8]))|(((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00))-0?2-29-)) (20|21|22|23|[0-1]?\\d):[0-5]?\\d:[0-5]?\\d$";
        /**
         * 验证IP
         */
        String IP = "^(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])$";
        /**
         * 验证URL
         */
        String Url = "^http(s)?://([\\w-]+\\.)+[\\w-]+(/[\\w- ./?%&=]*)?$";
        /**
         * 验证浮点数
         */
        String Float = "^(-?\\d+)(\\.\\d+)?$";
        /**
         * 验证整数
         */
        String Integer = "^-?\\d+$";
        /**
         * 验证正浮点数
         */
        String PlusFloat = "^(([0-9]+\\.[0-9]*[1-9][0-9]*)|([0-9]*[1-9][0-9]*\\.[0-9]+)|([0-9]*[1-9][0-9]*))$";
        /**
         * 验证正整数
         */
        String PlusInteger = "^[0-9]*[1-9][0-9]*$";
        /**
         * 验证负浮点数
         */
        String MinusFloat = "^(-(([0-9]+\\.[0-9]*[1-9][0-9]*)|([0-9]*[1-9][0-9]*\\.[0-9]+)|([0-9]*[1-9][0-9]*)))$";
        /**
         * 验证负整数
         */
        String MinusInteger = "^-[0-9]*[1-9][0-9]*$";
        /**
         * 验证非负浮点数（正浮点数 + 0）
         */
        String UnMinusFloat = "^\\d+(\\.\\d+)?$";
        /**
         * 验证非负整数（正整数 + 0）
         */
        String UnMinusInteger = "^\\d+$";
        /**
         * 验证非正浮点数（负浮点数 + 0）
         */
        String UnPlusFloat = "^((-\\d+(\\.\\d+)?)|(0+(\\.0+)?))$";
        /**
         * 验证非正整数（负整数 + 0）
         */
        String UnPlusInteger = "^((-\\d+)|(0+))$";
        /**
         * 验证由数字组成的字符串
         */
        String Numeric = "^[0-9]+$";
        /**
         * 验证由数字和26个英文字母组成的字符串
         */
        String NumericOrLetter = "^[A-Za-z0-9]+$";
        /**
         * 验证由数字、26个英文字母或者下划线组成的字符串
         */
        String NumericOrLetterOrUnderline = "^\\w+$";
        /**
         * 验证由数字和26个英文字母或中文组成的字符串
         */
        String NumbericOrLetterOrChinese = "^[A-Za-z0-9\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";
        /**
         * 验证由26个英文字母组成的字符串
         */
        String Letter = "^[A-Za-z]+$";
        /**
         * 验证由26个英文字母的小写组成的字符串
         */
        String LowerLetter = "^[a-z]+$";
        /**
         * 验证由26个英文字母的大写组成的字符串
         */
        String UpperLetter = "^[A-Z]+$";
        /**
         * 验证由中文组成的字符串
         */
        String Chinese = "^[\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";
        /**
         * 检测是否符合邮编格式
         */
        String PostCode = "^\\d{6}$";
        /**
         * 验证颜色（#ff0000）
         */
        String Color = "^#[a-fA-F0-9]{6}";
        /**
         * 通过文件扩展名验证图像格式
         */
        String ImageFormat = "\\.(?i:jpg|bmp|gif|ico|pcx|jpeg|tif|png|raw|tga)$";
    }
    //#endregion

    public static boolean isMatch(String input, String regularExp) {
        return input != null && regularExp != null && Pattern.matches(input, regularExp);
    }
}
