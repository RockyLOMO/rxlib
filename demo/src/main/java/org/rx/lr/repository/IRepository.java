package org.rx.lr.repository;

import org.rx.NQuery;

import java.util.function.Function;
import java.util.function.Predicate;

public interface IRepository<T> {
    T save(T model);

    NQuery<T> query(Predicate<T> condition);

    <TK> NQuery<T> query(Predicate<T> condition, Function<T, TK> keySelector);

    <TK> NQuery<T> queryDescending(Predicate<T> condition, Function<T, TK> keySelector);
}
