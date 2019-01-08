package org.rx.fl.util;

import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.bean.DateTime;
import org.rx.fl.repository.MyBatisBaseDao;
import org.rx.util.SpringContextUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.Date;

import static org.rx.Contract.require;

@Component
public class DbUtil {
    public static final String IsDeleted_True = "Y", IsDeleted_False = "N";
    private static final String mapperScan = (String) App.readSetting("app.mybatis.mapperScan");

    public static double toMoney(Long cent) {
        if (cent == null) {
            return 0;
        }
        return (double) cent / 100;
    }

    public static long toCent(String money) {
        if (StringUtils.isBlank(money)) {
            return 0;
        }
        return ((Double) (Double.valueOf(money) * 100)).longValue();
    }

    public <T> T selectById(MyBatisBaseDao mapper, String id) {
        require(mapper, id);

        T t = (T) mapper.selectByPrimaryKey(id);
        if (t == null) {
            throw new InvalidOperationException(String.format("%s.selectByPrimaryKey(%s) is null", mapper.getClass().getSimpleName(), id));
        }
        return t;
    }

    public <T> T save(T model) {
        return save(model, false);
    }

    @Transactional
    public <T> T save(T model, boolean forceInsert) {
        require(model);

        MyBatisBaseDao mapper = getMapper(model.getClass());
        boolean isInsert = false;
        String id = getValue(model, "id");
        if (id == null) {
            setValue(model, "id", id = App.newComb(false).toString());
            isInsert = true;
        }
        Date createTime = getValue(model, "createTime");
        if (createTime == null) {
            setValue(model, "createTime", createTime = DateTime.now());
        }
        Date modifyTime = getValue(model, "modifyTime");
        if (modifyTime == null) {
            setValue(model, "modifyTime", modifyTime = createTime);
        }
        String isDeleted = getValue(model, "isDeleted");
        if (isDeleted == null) {
            setValue(model, "isDeleted", isDeleted = "N");
        }

        if (forceInsert || isInsert) {
            mapper.insertSelective(model);
        } else {
            mapper.updateByPrimaryKeySelective(model);
        }
        return model;
    }

    private MyBatisBaseDao getMapper(Class modelType) {
        String className = String.format("%s.%sMapper", mapperScan, modelType.getTypeName());
        return SpringContextUtil.getBean(App.loadClass(className, false));
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
