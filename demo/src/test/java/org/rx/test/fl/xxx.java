package org.rx.test.fl;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 类工具类
 * 1、扫描指定包下的所有类
 * @see http://www.oschina.net/code/snippet_234657_22722
 * @author seaside_hi, xiaoleilu
 *
 */
public class ClassUtil {
    private static Logger log = LoggerFactory.getLogger(ClassUtil.class);

    /** Class文件扩展名 */
    private static final String CLASS_EXT = ".class";
    /** Jar文件扩展名 */
    private static final String JAR_FILE_EXT = ".jar";
    /** 在Jar中的路径jar的扩展名形式 */
    private static final String JAR_PATH_EXT = ".jar!";
    /** 当Path为文件形式时, path会加入一个表示文件的前缀 */
    private static final String PATH_FILE_PRE = "file:";
    /** 扫描Class文件时空的过滤器，表示不过滤 */
    private static final ClassFilter NULL_CLASS_FILTER = null;


    /**
     * 基本变量类型的枚举
     * @author xiaoleilu
     */
    private static enum BASIC_TYPE {
        BYTE, SHORT, INT, INTEGER, LONG, DOUBLE, FLOAT, BOOLEAN, CHAR, CHARACTER, STRING;
    }

    private ClassUtil() {
    }

    /**
     * 扫面改包路径下所有class文件
     *
     * @param packageName 包路径 com | com. | com.abs | com.abs.
     * @return
     */
    public static Set<Class<?>> scanPackage(String packageName) {
        log.debug("Scan classes from package [{}]...", packageName);
        return scanPackage(packageName, NULL_CLASS_FILTER);
    }

    /**
     * 扫面包路径下满足class过滤器条件的所有class文件，</br>
     * 如果包路径为 com.abs + A.class 但是输入 abs会产生classNotFoundException</br>
     * 因为className 应该为 com.abs.A 现在却成为abs.A,此工具类对该异常进行忽略处理,有可能是一个不完善的地方，以后需要进行修改</br>
     *
     * @param packageName 包路径 com | com. | com.abs | com.abs.
     * @param classFilter class过滤器，过滤掉不需要的class
     * @return
     */
    public static Set<Class<?>> scanPackage(String packageName, ClassFilter classFilter) {
        if(StrUtil.isBlank(packageName)) throw new NullPointerException("packageName can't be blank!");
        packageName = getWellFormedPackageName(packageName);

        final Set<Class<?>> classes = new HashSet<Class<?>>();
        for (String classPath : getClassPaths(packageName)) {
            log.debug("Scan classpath: [{}]", classPath);
            // 填充 classes
            fillClasses(classPath, packageName, classFilter, classes);
        }

        //如果在项目的ClassPath中未找到，去系统定义的ClassPath里找
        if(classes.isEmpty()) {
            for (String classPath : getJavaClassPaths()) {
                log.debug("Scan java classpath: [{}]", classPath);
                // 填充 classes
                fillClasses(new File(classPath), packageName, classFilter, classes);
            }
        }
        return classes;
    }

    /**
     * 获得指定类中的Public方法名<br>
     * 去重重载的方法
     * @param clazz 类
     */
    public final static Set<String> getMethods(Class<?> clazz) {
        HashSet<String> methodSet = new HashSet<String>();
        Method[] methodArray = clazz.getMethods();
        for (Method method : methodArray) {
            String methodName = method.getName();
            methodSet.add(methodName);
        }
        return methodSet;
    }

    /**
     * 获得ClassPath
     * @return
     */
    public static Set<String> getClassPathResources(){
        return getClassPaths(StrUtil.EMPTY);
    }

    /**
     * 获得ClassPath
     * @param packageName 包名称
     * @return
     */
    public static Set<String> getClassPaths(String packageName){
        String packagePath = packageName.replace(StrUtil.DOT, StrUtil.SLASH);
        Enumeration<URL> resources;
        try {
            resources = Thread.currentThread().getContextClassLoader().getResources(packagePath);
        } catch (IOException e) {
            log.error("Error when load classPath!", e);
            return null;
        }
        Set<String> paths = new HashSet<String>();
        while(resources.hasMoreElements()) {
            paths.add(resources.nextElement().getPath());
        }
        return paths;
    }

    /**
     * 获得Java ClassPath路径，不包括 jre<br>
     * @return
     */
    public static String[] getJavaClassPaths() {
        String[] classPaths = System.getProperty("java.class.path").split(System.getProperty("path.separator"));
        return classPaths;
    }

    /**
     * 强制转换类型
     * @param clazz 被转换成的类型
     * @param value 需要转换的对象
     * @return 转换后的对象
     */
    public static Object parse(Class<?> clazz, Object value) {
        try {
            return clazz.cast(value);
        } catch (ClassCastException e) {
            String valueStr = String.valueOf(value);

            Object result = parseBasic(clazz, valueStr);
            if(result != null) {
                return result;
            }

            if(Date.class.isAssignableFrom(clazz)) {
                //判断标准日期
                return DateUtil.parse(valueStr);
            } else if(clazz == BigDecimal.class) {
                //数学计算数字
                return new BigDecimal(valueStr);
            }else if(clazz == byte[].class) {
                //流，由于有字符编码问题，在此使用系统默认
                return valueStr.getBytes();
            }

            //未找到可转换的类型，返回原值
            return value;
        }
    }

    /**
     * 转换基本类型
     * @param clazz 转换到的类
     * @param valueStr 被转换的字符串
     * @return 转换后的对象，如果非基本类型，返回null
     */
    public static Object parseBasic(Class<?> clazz, String valueStr) {
        switch (BASIC_TYPE.valueOf(clazz.getSimpleName().toUpperCase())) {
            case STRING:
                return valueStr;
            case BYTE:
                if(clazz == byte.class) {
                    return Byte.parseByte(valueStr);
                }
                return Byte.valueOf(valueStr);
            case SHORT:
                if(clazz == short.class) {
                    return Short.parseShort(valueStr);
                }
                return Short.valueOf(valueStr);
            case INT:
                return Integer.parseInt(valueStr);
            case INTEGER:
                return Integer.valueOf(valueStr);
            case LONG:
                if(clazz == long.class) {
                    return Long.parseLong(valueStr);
                }
                return Long.valueOf(valueStr);
            case DOUBLE:
                if(clazz == double.class) {
                    return Double.parseDouble(valueStr);
                }
            case FLOAT:
                if(clazz == float.class) {
                    return Float.parseFloat(valueStr);
                }
                return Float.valueOf(valueStr);
            case BOOLEAN:
                if(clazz == boolean.class) {
                    return Boolean.parseBoolean(valueStr);
                }
                return Boolean.valueOf(valueStr);
            case CHAR:
                return valueStr.charAt(0);
            case CHARACTER:
                return Character.valueOf(valueStr.charAt(0));
            default:
                return null;
        }
    }

    /**
     * 转换基本类型
     * @param clazz
     * @return
     */
    public static Class<?> castToPrimitive(Class<?> clazz) {
        BASIC_TYPE basicType;
        try {
            basicType = BASIC_TYPE.valueOf(clazz.getSimpleName().toUpperCase());
        }catch(Exception e) {
            return clazz;
        }
        //基本类型
        switch (basicType) {
            case BYTE:
                return byte.class;
            case SHORT:
                return short.class;
            case INT:
                return int.class;
            case LONG:
                return long.class;
            case DOUBLE:
                return double.class;
            case FLOAT:
                return float.class;
            case BOOLEAN:
                return boolean.class;
            case CHAR:
                return char.class;
            default:
                return clazz;
        }
    }

    /**
     * 当前线程的class loader
     * @return
     */
    public static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * 获得class loader<br>
     * 若当前线程class loader不存在，取当前类的class loader
     * @return
     */
    public static ClassLoader getClassLoader() {
        ClassLoader classLoader = getContextClassLoader();
        if(classLoader == null) {
            classLoader = ClassUtil.class.getClassLoader();
        }
        return classLoader;
    }

    //--------------------------------------------------------------------------------------------------- Private method start
    /**
     * 文件过滤器，过滤掉不需要的文件<br>
     * 只保留Class文件、目录和Jar
     */
    private static FileFilter fileFilter = new FileFilter(){
        @Override
        public boolean accept(File pathname) {
            return isClass(pathname.getName()) || pathname.isDirectory() || isJarFile(pathname);
        }
    };

    /**
     * 改变 com -> com. 避免在比较的时候把比如 completeTestSuite.class类扫描进去，如果没有"."
     * </br>那class里面com开头的class类也会被扫描进去,其实名称后面或前面需要一个 ".",来添加包的特征
     *
     * @param packageName
     * @return
     */
    private static String getWellFormedPackageName(String packageName) {
        return packageName.lastIndexOf(StrUtil.DOT) != packageName.length() - 1 ? packageName + StrUtil.DOT : packageName;
    }

    /**
     * 填充满足条件的class 填充到 classes<br>
     * 同时会判断给定的路径是否为Jar包内的路径，如果是，则扫描此Jar包
     *
     * @param path Class文件路径或者所在目录Jar包路径
     * @param packageName 需要扫面的包名
     * @param classFilter class过滤器
     * @param classes List 集合
     */
    private static void fillClasses(String path, String packageName, ClassFilter classFilter, Set<Class<?>> classes) {
        //判定给定的路径是否为
        int index = path.lastIndexOf(JAR_PATH_EXT);
        if(index != -1) {
            path = path.substring(0, index + JAR_FILE_EXT.length());
            path = StrUtil.removePrefix(path, PATH_FILE_PRE);
            processJarFile(new File(path), packageName, classFilter, classes);
        }else {
            fillClasses(new File(path), packageName, classFilter, classes);
        }
    }

    /**
     * 填充满足条件的class 填充到 classes
     *
     * @param file Class文件或者所在目录Jar包文件
     * @param packageName 需要扫面的包名
     * @param classFilter class过滤器
     * @param classes List 集合
     */
    private static void fillClasses(File file, String packageName, ClassFilter classFilter, Set<Class<?>> classes) {
        if (file.isDirectory()) {
            processDirectory(file, packageName, classFilter, classes);
        } else if (isClassFile(file)) {
            processClassFile(file, packageName, classFilter, classes);
        } else if (isJarFile(file)) {
            processJarFile(file, packageName, classFilter, classes);
        }
    }

    /**
     * 处理如果为目录的情况,需要递归调用 fillClasses方法
     *
     * @param directory
     * @param packageName
     * @param classFilter
     * @param classes
     */
    private static void processDirectory(File directory, String packageName, ClassFilter classFilter, Set<Class<?>> classes) {
        for (File file : directory.listFiles(fileFilter)) {
            fillClasses(file, packageName, classFilter, classes);
        }
    }

    /**
     * 处理为class文件的情况,填充满足条件的class 到 classes
     *
     * @param file
     * @param packageName
     * @param classFilter
     * @param classes
     */
    private static void processClassFile(File file, String packageName, ClassFilter classFilter, Set<Class<?>> classes) {
        final String filePathWithDot = file.getAbsolutePath().replace(File.separator, StrUtil.DOT);
        int subIndex = -1;
        if ((subIndex = filePathWithDot.indexOf(packageName)) != -1) {
            final String className = filePathWithDot.substring(subIndex).replace(CLASS_EXT, StrUtil.EMPTY);
            fillClass(className, packageName, classes, classFilter);
        }
    }

    /**
     * 处理为jar文件的情况，填充满足条件的class 到 classes
     *
     * @param file
     * @param packageName
     * @param classFilter
     * @param classes
     */
    private static void processJarFile(File file, String packageName, ClassFilter classFilter, Set<Class<?>> classes) {
        try {
            for (JarEntry entry : Collections.list(new JarFile(file).entries())) {
                if (isClass(entry.getName())) {
                    final String className = entry.getName().replace(StrUtil.SLASH, StrUtil.DOT).replace(CLASS_EXT, StrUtil.EMPTY);
                    fillClass(className, packageName, classes, classFilter);
                }
            }
        } catch (Throwable ex) {
            log.error(ex.getMessage(), ex);
        }
    }

    /**
     * 填充class 到 classes
     *
     * @param className
     * @param packageName
     * @param classes
     * @param classFilter
     */
    private static void fillClass(String className, String packageName, Set<Class<?>> classes, ClassFilter classFilter) {
        if (className.startsWith(packageName)) {
            try {
                final Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                if (classFilter == NULL_CLASS_FILTER || classFilter.accept(clazz)) {
                    classes.add(clazz);
                }
            } catch (Throwable ex) {
                log.error("", ex);
            }
        }
    }

    private static boolean isClassFile(File file) {
        return isClass(file.getName());
    }

    private static boolean isClass(String fileName) {
        return fileName.endsWith(CLASS_EXT);
    }

    private static boolean isJarFile(File file) {
        return file.getName().contains(JAR_FILE_EXT);
    }
    //--------------------------------------------------------------------------------------------------- Private method end

    /**
     * 类过滤器，用于过滤不需要加载的类
     * @see http://www.oschina.net/code/snippet_234657_22722
     * @author seaside_hi
     */
    public interface ClassFilter {
        boolean accept(Class<?> clazz);
    }
}
