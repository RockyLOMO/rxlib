package org.rx.lr.repository;

import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.repository.model.common.PagingParam;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public interface IRepository<T> {
    T save(T model);

    T single(Predicate<T> condition);

    List<T> list(Predicate<T> condition);

    <TK> List<T> list(Predicate<T> condition, Function<T, TK> keySelector);

    <TK> List<T> listDescending(Predicate<T> condition, Function<T, TK> keySelector);

    <TK> PagedResult<T> page(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam);

    <TK> PagedResult<T> pageDescending(Predicate<T> condition, Function<T, TK> keySelector, PagingParam pagingParam);
}
