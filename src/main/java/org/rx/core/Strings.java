package org.rx.core;

import org.apache.commons.lang3.StringUtils;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;

import io.netty.util.internal.ThreadLocalRandom;

import java.util.regex.Pattern;

import static org.rx.core.App.values;

public class Strings extends StringUtils {
    /**
     * 判断当前版本和最新版本的关系
     * <p>
     * 如果当前版本小于最新版本, 返回1,
     * 如果当前版本大于最新版本, 返回-1,
     * 如果当前版本等于最新版本, 返回0
     * <p>
     * 注意: 所有非数字字符都会被忽略
     * <p>
     * 例子: 0.1.6.9 和 0.1.6.9 返回0
     * 例子: 0.1.6.9 和 0.1.7.0 返回1
     * 例子: 0.1.69  和 0.17.0  返回1
     * 例子: 0.16.9  和 0.1.70  返回-1
     *
     * @param currentVersion 当前版本
     * @param latestVersion  最新版本
     * @return 对比值
     */
    public static int versionComparison(String currentVersion, String latestVersion) {
        if (currentVersion == null || latestVersion == null) return 0;
        String[] currentVersionAfterSplit = removeInNumeric(currentVersion).split("\\.");
        String[] latestVersionAfterSplit = removeInNumeric(latestVersion).split("\\.");

        int currentLength = currentVersionAfterSplit.length;
        int latestLength = latestVersionAfterSplit.length;

        for (int i = 0; i < Math.max(currentLength, latestLength); i++) {
            int currentVersionAtI = i < currentLength ? Integer.parseInt(currentVersionAfterSplit[i]) : 0;
            int latestVersionAtI = i < latestLength ? Integer.parseInt(latestVersionAfterSplit[i]) : 0;

            if (currentVersionAtI < latestVersionAtI) return 1;
            if (currentVersionAtI > latestVersionAtI) return -1;
        }
        return 0;
    }

    /**
     * 去掉字符串里除了数字/小数点外的字符
     *
     * @param string 源
     * @return 处理后
     */
    public static String removeInNumeric(String string) {
        if (isEmpty(string)) return EMPTY;

        java.lang.StringBuilder output = new java.lang.StringBuilder();
        for (Character aChar : string.toCharArray()) {
            if (Character.isDigit(aChar)) output.append(aChar);
            if (aChar == '.') output.append('.');
        }
        return output.toString();
    }

    /**
     * 替换最后一个
     *
     * @param text        源字符串
     * @param regex       要替换从的
     * @param replacement 要替换成的
     * @return 替换后的字符串
     */
    public static String replaceLast(String text, String regex, String replacement) {
        return text.replaceFirst("(?s)" + regex + "(?!.*?" + regex + ")", replacement);
    }

    /**
     * 替换变量
     * 变量格式: %{变量名}
     *
     * @param original                 源字符串
     * @param variablesAndReplacements 变量名和值
     * @return 替换完的字符串
     */
    public static String replaceVariables(String original, Object... variablesAndReplacements) {
        java.lang.StringBuilder builder = new java.lang.StringBuilder();

        boolean first = true;
        for (String line : original.split("\n")) {
            for (int i = 0; i < variablesAndReplacements.length; i += 2)
                line = line.replace("%{" + String.valueOf(variablesAndReplacements[i]) + "}",
                        String.valueOf(variablesAndReplacements[i + 1]));

            if (first) {
                builder.append(line);
                first = false;
            } else builder.append("\n").append(line);
        }

        return builder.toString();
    }

    public static String randomValue(int maxValue) {
        Integer int2 = maxValue;
        return String.format("%0" + int2.toString().length() + "d", ThreadLocalRandom.current().nextInt(maxValue));
    }

    public static String maskPrivacy(String val) {
        if (isEmpty(val)) {
            return EMPTY;
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

    @ErrorCode("lengthError")
    public static String[] split(String str, String delimiter, int fixedLength) {
        String[] result;
        if (isEmpty(str)) {
            result = new String[0];
        } else {
            result = str.split(Pattern.quote(delimiter));
        }
        if (fixedLength > -1 && fixedLength != result.length) {
            throw new ApplicationException("lengthError", values(fixedLength));
        }
        return result;
    }

    //region Nested
    public interface RegularExp {
        /**
         * 验证email地址
         */
        String Email = "^[\\w-]+(\\.[\\w-]+)*@[\\w-]+(\\.[\\w-]+)+$";
        /**
         * 身份证
         */
        String CitizenId = "^(\\d{15}$|^\\d{18}$|^\\d{17}(\\d|X|x))$";
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
        String Url = "^((http|https|ftp):\\/\\/)?(\\w(\\:\\w)?@)?([0-9a-z_-]+\\.)*?([a-z0-9-]+\\.[a-z]{2,6}(\\.[a-z]{2})?(\\:[0-9]{2,6})?)((\\/[^?#<>\\/\\\\*\":]*)+(\\?[^#]*)?(#.*)?)?$";
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
        String NumericOrLetterOrChinese = "^[A-Za-z0-9\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";
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
        return input != null && regularExp != null && Pattern.matches(regularExp, input);
    }
}
