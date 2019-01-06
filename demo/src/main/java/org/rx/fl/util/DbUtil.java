package org.rx.fl.util;

import lombok.SneakyThrows;
import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.NQuery;
import org.rx.bean.DateTime;
import org.rx.util.SpringContextUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Date;

import static org.rx.Contract.require;

@Component
public class DbUtil {
    private static final String mapperScan = (String) App.readSetting("app.mybatis.mapperScan");

    public static double toMoney(long cent) {
        return (double) cent / 100;
    }

    public <TMapper, T> T selectByPrimaryKey(TMapper mapper, String id) {
        require(mapper, id);

        T t = invoke(mapper, "selectByPrimaryKey", id);
        if (t == null) {
            throw new InvalidOperationException(String.format("%s.selectByPrimaryKey(%s) is null", mapper.getClass().getSimpleName(), id));
        }
        return t;
    }

    @Transactional
    public <T> T save(T model) {
        require(model);

        String className = String.format("%s.%sMapper", mapperScan, model.getClass().getTypeName());
        Object mapper = SpringContextUtil.getBean(App.loadClass(className, false));

        boolean isInsert = false;
        String id = getValue(model, "id");
        if (id == null) {
            setValue(model, "id", id = App.newComb(false).toString());
            isInsert = true;
        }
        Date createTime = getValue(model, "createTime");
        if (createTime == null) {
            setValue(model, "createTime", createTime = DateTime.utcNow());
        }
        Date modifyTime = getValue(model, "modifyTime");
        if (modifyTime == null) {
            setValue(model, "modifyTime", modifyTime = createTime);
        }
        String isDeleted = getValue(model, "isDeleted");
        if (isDeleted == null) {
            setValue(model, "isDeleted", isDeleted = "N");
        }

        if (isInsert) {
            invoke(mapper, "insertSelective", model);
        } else {
            invoke(mapper, "updateByPrimaryKeySelective", model);
        }
        return model;
    }

    @SneakyThrows
    private <T> T invoke(Object mapper, String methodName, Object... args) {
        Method method = mapper.getClass().getDeclaredMethod(methodName, NQuery.of(args).select(p -> (Class) p.getClass()).toArray(Class.class));
        return (T) method.invoke(mapper, args);
    }

    @SneakyThrows
    private <T> T getValue(Object model, String name) {
        Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(model);
    }

    @SneakyThrows
    private void setValue(Object model, String name, Object val) {
        Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(model, App.changeType(val, field.getType()));
    }
}
