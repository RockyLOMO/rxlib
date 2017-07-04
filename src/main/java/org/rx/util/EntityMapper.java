package org.rx.util;

import org.rx.common.Func2;
import org.rx.common.Action2;
import org.rx.common.Func1;
import org.rx.common.NQuery;
import org.rx.security.MD5Util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by wangxiaoming on 2016/3/11.
 */
public class EntityMapper {
    //region NestedTypes
    private static class MapEntity {
        public Func2<String, String, Boolean> MembersMatcher;
        public Object PostProcessor;
        public HashSet<String> IgnoreNames;
    }

    private static class DefaultMatcher implements Func2<String, String, Boolean> {
        @Override
        public Boolean invoke(String arg1, String arg2) {
            return arg1.equals(arg2);
        }
    }
    //endregion

    //region Fields
    private static final String GET = "get", SET = "set";
    private static final int getDefaultWhenNull = 1 << 0, putNewWhenNull = 1 << 1;
    private static final MapEntity Default;
    private static ConcurrentMap<String, MapEntity> Config;
    //endregion

    //region Methods
    static {
        Default = new MapEntity();
        Default.IgnoreNames = new HashSet<>();
        Default.IgnoreNames.add("getClass");
        Default.MembersMatcher = new DefaultMatcher();
        Config = new ConcurrentHashMap<>();
    }

    private static MapEntity getConfig(Class<?> tFrom, Class<?> tTo, int flags) {
        String key = getKey(tFrom, tTo);
        MapEntity map = Config.get(key);
        if (map == null) {
            if ((flags & getDefaultWhenNull) == getDefaultWhenNull) {
                return Default;
            }
            if ((flags & putNewWhenNull) == putNewWhenNull) {
                Config.putIfAbsent(key, map = new MapEntity());
                map.MembersMatcher = Default.MembersMatcher;
            }
        }
        return map;
    }

    private static String getKey(Class<?> tFrom, Class<?> tTo) {
        return MD5Util.md5Hex(tFrom.getName() + tTo.getName());
    }

    public static <T> T createInstance(Class<T> type) {
        try {
            return type.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
    //endregion

    //region MapMethods
    public synchronized static <TF, TT> void setMembersMatcher(Class<TF> tFrom, Class<TT> tTo, Func2<String, String, Boolean> membersMatcher, Action2<TF, TT> postProcessor) {
        MapEntity map = getConfig(tFrom, tTo, putNewWhenNull);
        map.MembersMatcher = membersMatcher == null ? Default.MembersMatcher : membersMatcher;
        map.PostProcessor = postProcessor;
    }

    public synchronized static void setIgnoreMembers(Class<?> tFrom, Class<?> tTo, String... ignoreNames) {
        MapEntity map = getConfig(tFrom, tTo, putNewWhenNull);
        map.IgnoreNames = new HashSet<>(Arrays.asList(ignoreNames));
        map.IgnoreNames.add("getClass");
    }

    public static <TF, TT> TT[] map(Collection<TF> from, Class<TT> toType) {
        List<TT> toSet = new ArrayList<>();
        for (Object o : from) {
            toSet.add(map(o, toType));
        }
        TT[] x = (TT[]) Array.newInstance(toType, toSet.size());
        toSet.toArray(x);
        return x;
    }

    public static <TF, TT> TT map(TF from, Class<TT> toType) {
        return map(from, createInstance(toType));
    }

    public static <TF, TT> TT map(TF from, TT to) {
        return map(from, to, false);
    }

    public static <TF, TT> TT map(TF from, TT to, boolean skipNull) {
        Class<?> tFrom = from.getClass(), tTo = to.getClass();
        final MapEntity map = getConfig(tFrom, tTo, getDefaultWhenNull);

        NQuery<Method> fq = new NQuery<>(tFrom.getMethods()).where(new Func1<Method, Boolean>() {
            @Override
            public Boolean invoke(Method arg) {
                String fName = arg.getName();
                return !map.IgnoreNames.contains(fName) && fName.startsWith(GET);
            }
        });
        NQuery<Method> tq = new NQuery<>(tTo.getMethods()).where(new Func1<Method, Boolean>() {
            @Override
            public Boolean invoke(Method arg) {
                return arg.getName().startsWith(SET);
            }
        });

        for (Method fMethod : fq) {
            String fName = fMethod.getName();
            final String tName = SET + fName.substring(3);
            //App.logInfo("EntityMapper Step1 %s", fName);

            Method tMethod = tq.where(new Func1<Method, Boolean>() {
                @Override
                public Boolean invoke(Method arg) {
                    return map.MembersMatcher.invoke(tName, arg.getName());
                }
            }).firstOrDefault();
            Class<?>[] tArgs;
            if (tMethod == null || (tArgs = tMethod.getParameterTypes()).length != 1) {
                //App.logInfo("EntityMapper %s Miss %s.%s", tTo.getSimpleName(), tFrom.getSimpleName(), tName);
                continue;
            }
            //App.logInfo("EntityMapper Step2 %s to %s", fName, tName);

            try {
                Object value = fMethod.invoke(from);
                if (value == null && skipNull) {
                    continue;
                }
                value = App.changeType(value, tArgs[0]);
                tMethod.invoke(to, value);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }

        if (map.PostProcessor != null) {
            Action2<TF, TT> postProcessor = (Action2<TF, TT>) map.PostProcessor;
            postProcessor.invoke(from, to);
        }
        return to;
    }
    //endregion

    public static void trim(Object entity) {
        Class<?> type = entity.getClass();
        NQuery<Method> fq = new NQuery<>(type.getMethods()).where(new Func1<Method, Boolean>() {
            @Override
            public Boolean invoke(Method arg) {
                return arg.getName().startsWith(GET) && arg.getReturnType().equals(String.class);
            }
        });

        for (Method method : fq) {
            try {
                String value = (String) method.invoke(entity);
                if (App.isNullOrEmpty(value)) {
                    continue;
                }
                method = type.getMethod(SET + method.getName().substring(3), String.class);
                method.invoke(entity, value.trim());
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static Object getProperty(Object entity, String propName) {
        try {
            Method method = entity.getClass().getMethod(GET + propName.substring(0, 1).toUpperCase() + propName.substring(1));
            return method.invoke(entity);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
