package org.rx.lr.repository.impl;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.config.Configuration;
import lombok.SneakyThrows;
import org.rx.App;
import org.rx.NQuery;
import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.common.DataObject;
import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.repository.model.common.PagingParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

@Component
public class Db4oRepository<T> implements IRepository<T> {
    protected static final String DbName = "rx";
    private Configuration config;

    @SneakyThrows
    public Db4oRepository() {
        config = Db4o.newConfiguration();
        config.setBlobPath(App.getBootstrapPath() + "\\" + DbName + ".dat");
    }

    protected <R> R invoke(Function<ObjectContainer, R> func) {
        return NQuery.of(invoke((Function<ObjectContainer, R>[]) new Function[]{func})).firstOrDefault();
    }

    protected <R> List<R> invoke(Function<ObjectContainer, R>... funcList) {
        require(funcList);

        List<R> result = new ArrayList<>();
        ObjectContainer db = Db4o.openFile(config, DbName);
        try {
            for (Function<ObjectContainer, R> function : funcList) {
                result.add(function.apply(db));
            }
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.close();
        }
        return result;
    }

    public T save(T model) {
        require(model);
        if (!(model instanceof DataObject)) {
            throw new IllegalArgumentException("model is not a DataObject");
        }

        return invoke(db -> {
            db.store(model);
            return model;
        });
    }

    @Override
    public T single(Predicate<T> condition) {
        return null;
    }

    @Override
    public List<T> list(Predicate<T> condition) {
        return null;
    }

    @Override
    public <TK> List<T> list(Predicate<T> condition, Function<T, TK> keySelector) {
        return null;
    }

    @Override
    public <TK> List<T> listDescending(Predicate<T> condition, Function<T, TK> keySelector) {
        return null;
    }

    @Override
    public <TK> PagedResult<T> page(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam) {
        return null;
    }

    @Override
    public <TK> PagedResult<T> pageDescending(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam) {
        return null;
    }

    public NQuery<T> query(Predicate<T> condition) {
        return query(condition, null);
    }

    public <TK> NQuery<T> query(Predicate<T> condition, Function<T, TK> keySelector) {
        return query(condition, keySelector, false);
    }

    public <TK> NQuery<T> queryDescending(Predicate<T> condition, Function<T, TK> keySelector) {
        return query(condition, keySelector, true);
    }

    private <TK> PagedResult<T> query(Predicate<T> condition, Function<T, TK> keySelector, boolean isDescending, PagingParam pagingParam) {
        require(condition);

        com.db4o.query.Predicate<T> predicate = new com.db4o.query.Predicate<T>() {
            public boolean match(T candidate) {
                return condition.test(candidate);
            }
        };
        List<T> list = invoke(db -> {
            if (keySelector == null) {
                return db.query(predicate);
            }

            Comparator<T> comparator = getComparator(keySelector);
            if (isDescending) {
                comparator = comparator.reversed();
            }
            return db.query(predicate, comparator);
        });
        if (pagingParam == null) {
            PagedResult<T> result = new PagedResult<>();
            result.setResultSet(list);
            return result;
        }
        return pagingParam.page(NQuery.of(list));
    }

    private <TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        if (keySelector == null) {
            return (Comparator<T>) Comparator.naturalOrder();
        }

        return (p1, p2) -> {
            Comparable c1 = as(keySelector.apply(p1), Comparable.class);
            Comparable c2 = as(keySelector.apply(p2), Comparable.class);
            if (c1 == null || c2 == null) {
                return 0;
            }
            return c1.compareTo(c2);
        };
    }
}
