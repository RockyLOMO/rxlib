package org.rx.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 此类由 Hykilpikonna 在 2018/09/16 创建!
 * Created by Hykilpikonna on 2018/09/16!
 * Github: https://github.com/hykilpikonna
 * QQ: admin@moecraft.cc -OR- 871674895
 *
 * @author Hykilpikonna
 */
@SuppressWarnings("WeakerAccess")
public class ArgsUtils
{
    /**
     * 从Main的args获取Operations
     * @param args args
     * @return 操作列表
     */
    public static ArrayList<String> getOperations(String[] args)
    {
        ArrayList<String> result = new ArrayList<>();

        for (String arg : args)
        {
            if (arg.startsWith("-")) break;

            result.add(arg);
        }

        return result;
    }

    private static final Pattern patternToFindOptions = Pattern.compile("(?<=-).*?(?==)");

    /**
     * 从Main的args获取Options
     * @param args args
     * @return 参数列表
     */
    public static Map<String, String> getOptions(String[] args)
    {
        Map<String, String> result = new HashMap<>();

        for (String arg : args)
        {
            if (arg.startsWith("-"))
            {
                Matcher matcher = patternToFindOptions.matcher(arg);
                if (matcher.find()) result.put(matcher.group(), arg.replaceFirst("-.*?=", ""));
            }
        }

        return result;
    }

    /**
     * 从Main的args构造Args对象
     * @param args args
     * @return Args对象
     */
    public static Args parse(String[] args)
    {
        return new Args(getOperations(args), getOptions(args));
    }
}
