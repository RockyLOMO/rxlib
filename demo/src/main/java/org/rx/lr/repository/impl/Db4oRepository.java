package org.rx.lr.repository.impl;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import lombok.SneakyThrows;
import org.rx.App;
import org.rx.NQuery;
import org.rx.SystemException;
import org.rx.bean.DateTime;
import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.common.DataObject;
import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.repository.model.common.PagingParam;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

@Component
public class Db4oRepository<T extends DataObject> implements IRepository<T> {
    private String dbPath;
    private Configuration config;

    @SneakyThrows
    public Db4oRepository() {
        dbPath = (String) App.readSetting("app.dbPath");
        if (dbPath == null) {
            throw SystemException.wrap(new IllegalArgumentException("app.dbPath配置为空"));
        }
        String dir = dbPath;
        int i = dir.lastIndexOf(".");
        if (i != -1) {
            dir = dir.substring(0, i);
        }
        App.createDirectory(dir);

        config = Db4o.newConfiguration();
    }

    protected <R> R invoke(Function<ObjectContainer, R> func) {
        return NQuery.of(invoke((Function<ObjectContainer, R>[]) new Function[]{func})).firstOrDefault();
    }

    protected synchronized <R> List<R> invoke(Function<ObjectContainer, R>... funcList) {
        require(funcList);

        List<R> result = new ArrayList<>();
//        ObjectContainer db = Db4o.openFile(config, dbPath);
        ObjectContainer db = App.getOrStore(Db4oRepository.class, "threadDb", k -> Db4o.openFile(config, dbPath));
        try {
            for (Function<ObjectContainer, R> function : funcList) {
                result.add(function.apply(db));
            }
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        }
//        finally {
//            db.close();
//        }
        return result;
    }

    public T save(T model) {
        require(model);
        if (!(model instanceof DataObject)) {
            throw new IllegalArgumentException("model is not a DataObject");
        }

        return invoke(db -> {
            T dataObj = single(p -> p.getId().equals(model.getId()));
            if (dataObj != null) {
                dataObj = BeanMapper.getInstance().map(model, dataObj, BeanMapper.Flags.NonCheckMatch | BeanMapper.Flags.SkipNull);
            } else {
                dataObj = model;
            }
            if (dataObj.getId() == null) {
                dataObj.setId(UUID.randomUUID());
            }
            if (dataObj.getCreateTime() == null) {
                dataObj.setCreateTime(DateTime.now());
            }
            dataObj.setModifyTime(DateTime.now());
            db.store(dataObj);
            return dataObj;
        });
    }

    @Override
    public T delete(UUID id) {
        T model = single(id);
        if (model == null) {
            return null;
        }
        model.setDeleted(true);
        return save(model);
    }

    @Override
    public T single(UUID id) {
        return single(p -> p.getId().equals(id));
    }

    @Override
    public T single(Predicate<T> condition) {
        return NQuery.of(list(condition)).firstOrDefault();
    }

    @Override
    public long count(Predicate<T> condition) {
        return executeReader(condition, null, false).count();
    }

    @Override
    public List<T> list(Predicate<T> condition) {
        return list(condition, null);
    }

    @Override
    public <TK> List<T> list(Predicate<T> condition, Function<T, TK> keySelector) {
        return executeReader(condition, keySelector, false).toList();
    }

    @Override
    public <TK> List<T> listDescending(Predicate<T> condition, Function<T, TK> keySelector) {
        return executeReader(condition, keySelector, true).toList();
    }

    @Override
    public <TK> PagedResult<T> page(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam) {
        require(pagingParam);

        NQuery<T> nQuery = executeReader(condition, keySelector, false);
        return pagingParam.page(nQuery);
    }

    @Override
    public <TK> PagedResult<T> pageDescending(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam) {
        require(pagingParam);

        NQuery<T> nQuery = executeReader(condition, keySelector, true);
        return pagingParam.page(nQuery);
    }

    private <TK> NQuery<T> executeReader(Predicate<T> condition, Function<T, TK> keySelector, boolean isDescending) {
        require(condition);

        com.db4o.query.Predicate<T> predicate = new com.db4o.query.Predicate<T>() {
            public boolean match(T candidate) {
                return !candidate.isDeleted() && condition.test(candidate);
            }
        };
        return invoke(db -> {
            ObjectSet<T> objectSet;
            if (keySelector == null) {
                objectSet = db.query(predicate);
            } else {
                Comparator<T> comparator = getComparator(keySelector);
                if (isDescending) {
                    comparator = comparator.reversed();
                }
                objectSet = db.query(predicate, comparator);
            }
            return NQuery.of(objectSet);
        });
    }

    private <TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        if (keySelector == null) {
            return (Comparator) Comparator.naturalOrder();
        }

        return NQuery.getComparator(keySelector);
    }
}
