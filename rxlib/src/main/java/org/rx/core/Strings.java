package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.rx.exception.InvalidException;

import java.awt.*;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Strings extends StringUtils {
    //region VarExpression
    public static String trimVarExpressionName(String exprName) {
        if (exprName == null) {
            return null;
        }
        int s = 0, e = exprName.length();
        if (exprName.startsWith("${")) {
            s = 2;
        }
        if (exprName.endsWith("}")) {
            e = exprName.length() - 1;
        }
        return exprName.substring(s, e);
    }

    /**
     * ${varName}
     *
     * @param expr This is ${name}
     * @return name
     */
    public static List<String> getVarExpressionNames(@NonNull String expr, boolean onlyName) {
        List<String> list = new ArrayList<>();
        int s = 0, e;
        while ((s = expr.indexOf("${", s)) != -1) {
            if ((e = expr.indexOf("}", s += 2)) == -1) {
                throw new InvalidException("Invalid expression");
            }
            list.add(onlyName ? expr.substring(s, e) : expr.substring(s - 2, e + 1));
        }
        return list;
    }

    /**
     * 替换变量
     *
     * @param expr This is ${name}
     * @param vars {\"name\": \"xf\"}
     * @return This is xf
     */
    public static String resolveVarExpression(@NonNull CharSequence expr, Map<String, Object> vars) {
        if (vars == null) {
            vars = Collections.emptyMap();
        }
        final char begin0 = '$', begin1 = '{', end = '}';
        final byte inExpr = 2;
        int len = expr.length(), mark = -1;
        StringBuilder buf = new StringBuilder(len), pBuf = new StringBuilder();
        byte stat = 0;
        for (int i = 0; i < len; i++) {
            char c = expr.charAt(i);
            if (c == begin0) {
                if (stat == 0) {
                    mark = buf.length();
                    stat = 1;
//                    continue;
                } else {
                    if (stat == inExpr) {
                        throw new InvalidException("表达式在索引{}开始，索引{}格式错误", mark, i);
                    }
                    mark = -1;
                    stat = 0;
                }
            } else if (c == begin1) {
                if (stat == 1) {
                    buf.append(c);
                    stat = inExpr;
                    continue;
                } else {
                    if (stat == inExpr) {
                        throw new InvalidException("表达式{}在索引{}开始，索引{}格式错误", pBuf, mark, i);
                    }
                    mark = -1;
                    stat = 0;
                }
            } else if (c == end) {
                if (stat == inExpr) {
                    buf.setLength(mark);
                    Object var = Sys.readJsonValue(vars, pBuf.toString(), null, false);
                    if (var != null) {
                        buf.append(var);
                    }
                    pBuf.setLength(0);
                    mark = -1;
                    stat = 0;
                    continue;
                } else {
                    mark = -1;
                    stat = 0;
                }
            }
            if (stat == inExpr) {
                pBuf.append(c);
                continue;
            }
            buf.append(c);
        }
        return buf.toString();
    }

    /**
     * 简易模板渲染，适合后台/诊断 HTML，不用于高频热点路径。
     *
     * <pre>
     * {{name}}                 HTML 转义输出
     * {{{html}}} 或 {{& html}}  原样输出
     * {{#if name}}...{{/if}}
     * {{#unless name}}...{{/unless}}
     * {{#each rows}}...{{/each}}，循环内支持 {{.}}、{{this}}、{{@index}}、{{@first}}、{{@last}}
     * ${name}                  兼容旧的原样变量语法
     * </pre>
     */
    public static String renderTemplate(@NonNull CharSequence template, Map<String, Object> vars) {
        return renderTemplateBlock(template, 0, template.length(),
                new TemplateContext(vars == null ? Collections.emptyMap() : vars, null));
    }

    public static String escapeHtml(Object value) {
        if (value == null) {
            return EMPTY;
        }
        String text = String.valueOf(value);
        if (text.length() == 0) {
            return EMPTY;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static String renderTemplateBlock(CharSequence template, int start, int end, TemplateContext context) {
        StringBuilder out = new StringBuilder(end - start);
        int pos = start;
        while (pos < end) {
            int varStart = indexOf(template, "${", pos, end);
            int tagStart = indexOf(template, "{{", pos, end);
            int next = minPositive(varStart, tagStart);
            if (next < 0) {
                out.append(template.subSequence(pos, end));
                break;
            }
            out.append(template.subSequence(pos, next));
            if (next == varStart) {
                int close = indexOf(template, "}", next + 2, end);
                if (close < 0) {
                    throw new InvalidException("Invalid template variable at index {}", next);
                }
                Object value = context.resolve(template.subSequence(next + 2, close).toString().trim());
                if (value != null) {
                    out.append(value);
                }
                pos = close + 1;
                continue;
            }

            boolean raw = startsWith(template, "{{{", next, end);
            String closeToken = raw ? "}}}" : "}}";
            int tagBodyStart = next + (raw ? 3 : 2);
            int close = indexOf(template, closeToken, tagBodyStart, end);
            if (close < 0) {
                throw new InvalidException("Invalid template tag at index {}", next);
            }
            String token = template.subSequence(tagBodyStart, close).toString().trim();
            int afterTag = close + closeToken.length();
            if (token.startsWith("#each ")) {
                String name = token.substring(6).trim();
                Section section = findSection(template, afterTag, end, "each");
                appendEach(out, template, section, context.resolve(name), context);
                pos = section.afterEnd;
            } else if (token.startsWith("#if ")) {
                String name = token.substring(4).trim();
                Section section = findSection(template, afterTag, end, "if");
                if (isTruthy(context.resolve(name))) {
                    out.append(renderTemplateBlock(template, section.bodyStart, section.bodyEnd, context));
                }
                pos = section.afterEnd;
            } else if (token.startsWith("#unless ")) {
                String name = token.substring(8).trim();
                Section section = findSection(template, afterTag, end, "unless");
                if (!isTruthy(context.resolve(name))) {
                    out.append(renderTemplateBlock(template, section.bodyStart, section.bodyEnd, context));
                }
                pos = section.afterEnd;
            } else if (token.startsWith("/")) {
                pos = afterTag;
            } else {
                if (token.startsWith("&")) {
                    raw = true;
                    token = token.substring(1).trim();
                }
                Object value = context.resolve(token);
                if (value != null) {
                    out.append(raw ? String.valueOf(value) : escapeHtml(value));
                }
                pos = afterTag;
            }
        }
        return out.toString();
    }

    private static void appendEach(StringBuilder out, CharSequence template, Section section, Object value, TemplateContext context) {
        if (value == null) {
            return;
        }
        if (value instanceof Map) {
            appendIterator(out, template, section, ((Map<?, ?>) value).entrySet().iterator(), ((Map<?, ?>) value).size(), context);
            return;
        }
        if (value instanceof Iterable) {
            int size = value instanceof Collection ? ((Collection<?>) value).size() : -1;
            appendIterator(out, template, section, ((Iterable<?>) value).iterator(), size, context);
            return;
        }
        if (value instanceof Iterator) {
            appendIterator(out, template, section, (Iterator<?>) value, -1, context);
            return;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                out.append(renderTemplateBlock(template, section.bodyStart, section.bodyEnd,
                        context.child(Array.get(value, i), i, len)));
            }
        }
    }

    private static void appendIterator(StringBuilder out, CharSequence template, Section section,
                                       Iterator<?> iterator, int size, TemplateContext context) {
        int index = 0;
        while (iterator.hasNext()) {
            Object item = iterator.next();
            int knownSize = size >= 0 ? size : (iterator.hasNext() ? -1 : index + 1);
            out.append(renderTemplateBlock(template, section.bodyStart, section.bodyEnd,
                    context.child(item, index, knownSize)));
            index++;
        }
    }

    private static Section findSection(CharSequence template, int start, int end, String name) {
        int depth = 1;
        int pos = start;
        while (pos < end) {
            int tagStart = indexOf(template, "{{", pos, end);
            if (tagStart < 0) {
                break;
            }
            boolean raw = startsWith(template, "{{{", tagStart, end);
            String closeToken = raw ? "}}}" : "}}";
            int tokenStart = tagStart + (raw ? 3 : 2);
            int close = indexOf(template, closeToken, tokenStart, end);
            if (close < 0) {
                throw new InvalidException("Invalid template section {}", name);
            }
            String token = template.subSequence(tokenStart, close).toString().trim();
            if (!raw && (token.equals("#" + name) || token.startsWith("#" + name + " "))) {
                depth++;
            } else if (!raw && token.equals("/" + name)) {
                depth--;
                if (depth == 0) {
                    return new Section(start, tagStart, close + 2);
                }
            }
            pos = close + closeToken.length();
        }
        throw new InvalidException("Template section {} not closed", name);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0D;
        }
        if (value instanceof CharSequence) {
            return ((CharSequence) value).length() != 0;
        }
        if (value instanceof Collection) {
            return !((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return !value.getClass().isArray() || Array.getLength(value) != 0;
    }

    private static int minPositive(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static int indexOf(CharSequence text, String token, int start, int end) {
        int max = end - token.length();
        for (int i = Math.max(0, start); i <= max; i++) {
            if (startsWith(text, token, i, end)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(CharSequence text, String token, int start, int end) {
        if (start < 0 || start + token.length() > end) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (text.charAt(start + i) != token.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static final class Section {
        final int bodyStart;
        final int bodyEnd;
        final int afterEnd;

        Section(int bodyStart, int bodyEnd, int afterEnd) {
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
            this.afterEnd = afterEnd;
        }
    }

    private static final class TemplateContext {
        final Object model;
        final TemplateContext parent;

        TemplateContext(Object model, TemplateContext parent) {
            this.model = model;
            this.parent = parent;
        }

        Object resolve(String path) {
            if (path == null || path.length() == 0) {
                return null;
            }
            Object value = resolveLocal(path);
            if (value != null || parent == null) {
                return value;
            }
            return parent.resolve(path);
        }

        TemplateContext child(Object item, int index, int size) {
            Map<String, Object> vars = new HashMap<>(8);
            vars.put("this", item);
            vars.put(".", item);
            vars.put("@index", Integer.valueOf(index));
            vars.put("@first", Boolean.valueOf(index == 0));
            vars.put("@last", Boolean.valueOf(size >= 0 && index == size - 1));
            if (item instanceof Map.Entry) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                vars.put("key", entry.getKey());
                vars.put("value", entry.getValue());
            } else if (item instanceof Map) {
                vars.putAll((Map<? extends String, ?>) item);
            }
            return new TemplateContext(vars, this);
        }

        Object resolveLocal(String path) {
            if (".".equals(path)) {
                return model instanceof Map ? ((Map<?, ?>) model).get(".") : model;
            }
            if ("this".equals(path)) {
                return model instanceof Map && ((Map<?, ?>) model).containsKey("this") ? ((Map<?, ?>) model).get("this") : model;
            }
            if (path.startsWith("this.")) {
                Object item = resolveLocal("this");
                return readTemplateValue(item, path.substring(5));
            }
            return readTemplateValue(model, path);
        }
    }

    private static Object readTemplateValue(Object model, String path) {
        if (model == null) {
            return null;
        }
        if (model instanceof Map && ((Map<?, ?>) model).containsKey(path)) {
            return ((Map<?, ?>) model).get(path);
        }
        try {
            return Sys.readJsonValue(model, path, null, false);
        } catch (Throwable e) {
            return null;
        }
    }
    //endregion

    /**
     * 简单的计算字符串
     *
     * @param expression 字符串
     * @return 计算结果
     */
    public static double simpleEval(@NonNull String expression) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            // Grammar:
            // expression = term | expression `+` term | expression `-` term
            // term = factor | term `*` factor | term `/` factor
            // factor = `+` factor | `-` factor | `(` expression `)`
            //        | number | functionName factor | factor `^` factor
            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) x += parseTerm(); // addition
                    else if (eat('-')) x -= parseTerm(); // subtraction
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('*')) x *= parseFactor(); // multiplication
                    else if (eat('/')) x /= parseFactor(); // division
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor(); // unary plus
                if (eat('-')) return -parseFactor(); // unary minus

                double x;
                int startPos = this.pos;

                if (eat('(')) { // parentheses
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos, this.pos));
                } else throw new RuntimeException("Unexpected: " + (char) ch);

                if (eat('^')) x = Math.pow(x, parseFactor()); // exponentiation

                return x;
            }
        }.parse();
    }

    public static int compareVersion(@NonNull String version1, @NonNull String version2) {
        int len1 = version1.length();
        int len2 = version2.length();
        int p1 = 0;
        int p2 = 0;
        while (p1 < len1 || p2 < len2) {
            int num1 = 0;
            int num2 = 0;
            while (p1 < len1 && version1.charAt(p1) != '.') {
                num1 = num1 * 10 + version1.charAt(p1++) - '0';
            }
            p1++;
            while (p2 < len2 && version2.charAt(p2) != '.') {
                num2 = num2 * 10 + version2.charAt(p2++) - '0';
            }
            p2++;
            if (num1 == num2) {
                continue;
            }
            return num1 > num2 ? 1 : -1;
        }
        return 0;
    }

    public static boolean isStrongPwd(String input) {
        //^(?=.*\d)(?=.*[a-z])(?=.*[A-Z]).{8,16}
        //^(?=.*\d)(?=.*[a-z])(?=.*[A-Z])[a-zA-Z0-9~!@#$%^&*]{8,16}$
        int len = input.length();
        if (len < 8) {
            return false;
        }
        int strength = 0, f = 1 | 2 | 4;
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (Character.isLowerCase(c)) {
                strength |= 1;
            } else if (Character.isUpperCase(c)) {
                strength |= 2;
            } else if (Character.isDigit(c)) {
                strength |= 4;
            }
            if ((strength & f) == f) {
                return true;
            }
        }
        return false;
    }

    public static String maskPrivacy(String str) {
        if (isEmpty(str)) {
            return EMPTY;
        }

        str = str.trim();
        int len = str.length(), left, right;
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
        return str.substring(0, left) + x + str.substring(left + x.length());
    }

    public static String randomValue(int maxValue) {
        return String.format("%0" + String.valueOf(maxValue).length() + "d", ThreadLocalRandom.current().nextInt(maxValue));
    }

    public static String toNumeric(String str) {
        if (isEmpty(str)) {
            return EMPTY;
        }
        StringBuilder buf = new StringBuilder();
        int len = str.length();
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                continue;
            }
            buf.append(c);
        }
        return buf.toString();
    }

    //String hashcode has cached
    public static boolean hashEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.hashCode() != b.hashCode()) {
            return false;
        }
        return a.equals(b);
    }

    public static boolean equalsTrim(String a, String b) {
        return equals(trim(a), trim(b));
    }

    public static boolean equalsTrimIgnoreCase(String a, String b) {
        return equalsIgnoreCase(trim(a), trim(b));
    }

    public static boolean containsAll(String str, CharSequence... searches) {
        return str != null && Linq.from(searches).all(str::contains);
    }

    public static String replaceLast(String text, String regex, String replacement) {
        if (text == null || regex == null) {
            return text;
        }
        return text.replaceFirst("(?s)" + regex + "(?!.*?" + regex + ")", replacement);
    }

    //StringUtils.split() //性能更好
//    @ErrorCode("lengthError")
//    public static String[] split(String str, String delimiter, int fixedLength) {
//        String[] result;
//        if (str == null) { //"" split result length is 1
//            result = Arrays.EMPTY_STRING_ARRAY;
//        } else {
//            result = str.split(Pattern.quote(delimiter));
//        }
//        if (fixedLength > -1 && fixedLength != result.length) {
//            throw new ApplicationException("lengthError", values(fixedLength));
//        }
//        return result;
//    }

    public static String subStringByByteLen(String text, int length) {
        if (Strings.isEmpty(text)) {
            return "";
        }

        int count = 0, offset;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 256) {
                offset = 2;
            } else {
                offset = 1;
            }
            count += offset;
            if (count >= length) {
                int end = i + (offset == 2 ? 0 : 1);
                if (i == 0) {
                    break;
                }
                return text.substring(0, end);
            }
        }
        return text;
    }

    public static String toDecodeString(Font font) {
        String name = font.getName().replace(" ", "-");
        String style = font.isBold() && font.isItalic() ? "BOLDITALIC" :
                font.isBold() ? "BOLD" :
                        font.isItalic() ? "ITALIC" : "PLAIN";
        int size = font.getSize();
        return style.equals("PLAIN") ? name + "-" + size : name + "-" + style + "-" + size;
    }

    public static String cas(String input) {
        if (Strings.isEmpty(input)) {
            return "";
        }
        final String s = "AS(", e = ")";
        StringBuilder sb = new StringBuilder();
        if (input.startsWith(s) && input.endsWith(e)) {
            for (String c : Strings.split(input.substring(3, input.length() - 1), ",")) {
                sb.append((char) Integer.parseInt(c));
            }
        } else {
            sb.append(s);
            for (int i = 0; i < input.length(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append((int) input.charAt(i));
            }
            sb.append(e);
        }
        return sb.toString();
    }

    //region Regular
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
        String Mobile = "^0{0,1}1[3|4|5|6|7|8|9]\\d{9}$";
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
        /**
         * 大小写+数字
         */
        String PWD_STRENGTH = "^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z]).{8,40}$";

        String CN_NAME = "^[\\u4e00-\\u9fa5]{1,2}[\\u4e00-\\u9fa5·]{1,15}[\\u4e00-\\u9fa5]{1,2}$";

        String EN_NAME = "^[A-Za-z.'’-]{1,20}\\s[A-Za-z.'’-]{1,20}$";
    }
    //endregion

    public static boolean isMatch(CharSequence input, String regularExp) {
        return input != null && regularExp != null && Pattern.matches(regularExp, input);
    }
}
