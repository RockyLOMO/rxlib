package org.rx.lr.repository;

import org.rx.NQuery;

import java.util.function.Function;
import java.util.function.Predicate;

public interface IRepository<T> {
    T save(T model);

    T single(Predicate<T> condition);

    <TK> NQuery<T> list(Predicate<T> condition, Function<T, TK> keySelector);

    <TK> NQuery<T> listDescending(Predicate<T> condition, Function<T, TK> keySelector);
}
