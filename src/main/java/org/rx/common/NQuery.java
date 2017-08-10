package org.rx.common;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Created by wangxiaoming on 2016/3/3.
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 */
public class NQuery<T> implements Iterable<T> {
    //region Properties
    private List<T> current;

    public NQuery(T[] set) {
        if (set == null) {
            throw new IllegalArgumentException("set == null");
        }
        current = Arrays.asList(set);
    }

    public NQuery(Iterable<T> set) {
        if (set == null) {
            throw new IllegalArgumentException("set == null");
        }
        current = toList(set);
    }

    private NQuery(List<T> list) {
        current = list;
    }
    //endregion

    //region Methods
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private int offset;

            @Override
            public boolean hasNext() {
                return current.size() > offset;
            }

            @Override
            public T next() {
                return current.get(offset++);
            }

            @Override
            public void remove() {

            }
        };
    }

    private List<T> toList(Iterable<T> set) {
        List<T> list = new ArrayList<>();
        for (T t : set) {
            list.add(t);
        }
        return list;
    }
    //endregion

    //region PRMethods
    public NQuery<T> where(Func1<T, Boolean> selector) {
        List<T> result = new ArrayList<>();
        for (T t : current) {
            if (selector.invoke(t)) {
                result.add(t);
            }
        }
        return new NQuery<>(result);
    }

    public <TR> NQuery<TR> select(Func1<T, TR> selector) {
        List<TR> result = new ArrayList<>();
        for (T t : current) {
            result.add(selector.invoke(t));
        }
        return new NQuery<>(result);
    }

    public <TR> NQuery<TR> selectMany(Func1<T, Iterable<TR>> selector) {
        List<TR> result = new ArrayList<>();
        for (T t : current) {
            for (TR tr : selector.invoke(t)) {
                result.add(tr);
            }
        }
        return new NQuery<>(result);
    }
    //endregion

    //region JoinMethods
    public <TI, TR> NQuery<TR> join(Iterable<TI> inner, Func2<T, TI, Boolean> keySelector,
                                    Func2<T, TI, TR> resultSelector) {
        List<TR> result = new ArrayList<>();
        for (T t : current) {
            for (TI ti : inner) {
                if (!keySelector.invoke(t, ti)) {
                    continue;
                }
                result.add(resultSelector.invoke(t, ti));
            }
        }
        return new NQuery<>(result);
    }
    //endregion

    //region SetMethods
    public boolean any() {
        return current.size() > 0;
    }

    public boolean any(Func1<T, Boolean> selector) {
        return this.where(selector).any();
    }

    public NQuery<T> except(Iterable<T> set) {
        List<T> result = toList();
        for (T t : set) {
            result.remove(t);
        }
        return new NQuery<>(result);
    }

    public NQuery<T> intersect(Iterable<T> set) {
        List<T> result = toList();
        result.retainAll(toList(set));
        return new NQuery<>(result);
    }

    public NQuery<T> union(Iterable<T> set) {
        HashSet<T> result = new HashSet<>();
        result.addAll(current);
        result.addAll(toList(set));
        return new NQuery<>(result);
    }
    //endregion

    //region OrderingMethods
    public <TK> NQuery<T> orderBy(final Func1<T, TK> keySelector) {
        List<T> result = toList();
        Collections.sort(result, new Comparator<T>() {
            @Override
            public int compare(T o1, T o2) {
                TK tk = keySelector.invoke(o1);
                if (!Comparable.class.isAssignableFrom(tk.getClass())) {
                    return 0;
                }
                Comparable c = (Comparable) tk;
                return c.compareTo(keySelector.invoke(o2));
            }
        });
        return new NQuery<>(result);
    }

    public <TK> NQuery<T> orderByDescending(final Func1<T, TK> keySelector) {
        List<T> result = toList();
        Collections.sort(result, new Comparator<T>() {
            @Override
            public int compare(T o1, T o2) {
                TK tk = keySelector.invoke(o1);
                if (!Comparable.class.isAssignableFrom(tk.getClass())) {
                    return 0;
                }
                Comparable c = (Comparable) tk;
                int val = c.compareTo(keySelector.invoke(o2));
                if (val == 1) {
                    return -1;
                } else if (val == -1) {
                    return 1;
                }
                return val;
            }
        });
        return new NQuery<>(result);
    }
    //endregion

    //region GroupingMethods
    public <TK, TR> NQuery<TR> groupBy(Func1<T, TK> keySelector, Func1<Tuple<TK, NQuery<T>>, TR> resultSelector) {
        Map<TK, List<T>> map = new HashMap<>();
        for (T t : current) {
            TK key = keySelector.invoke(t);
            if (map.get(key) == null) {
                map.put(key, new ArrayList<T>());
            }
            map.get(key).add(t);
        }
        List<TR> result = new ArrayList<>();
        for (TK tk : map.keySet()) {
            result.add(resultSelector.invoke(new Tuple<>(tk, new NQuery<>(map.get(tk)))));
        }
        return new NQuery<>(result);
    }
    //endregion

    //region AggregateMethods
    public int count() {
        return current.size();
    }
    //endregion

    //region PagingMethods
    public T first() {
        return current.get(0);
    }

    public T firstOrDefault() {
        if (current.size() == 0) {
            return null;
        }
        return first();
    }

    public T last() {
        return current.get(current.size() - 1);
    }

    public T lastOrDefault() {
        if (current.size() == 0) {
            return null;
        }
        return last();
    }
    //endregion

    //region ToMethods
    public T[] toArray(Class<T> type) {
        T[] set = (T[]) Array.newInstance(type, current.size());
        current.toArray(set);
        return set;
    }

    public List<T> toList() {
        return new ArrayList<>(current);
    }

    public Set<T> toSet() {
        return new HashSet<>(current);
    }

    public <TK> Map<TK, T> toMap(Func1<T, TK> keySelector) {
        HashMap<TK, T> map = new HashMap<>();
        for (T t : current) {
            map.put(keySelector.invoke(t), t);
        }
        return map;
    }

    public <TK, TV> Map<TK, TV> toMap(Func1<T, TK> keySelector, Func1<T, TV> valueSelector) {
        HashMap<TK, TV> map = new HashMap<>();
        for (T t : current) {
            map.put(keySelector.invoke(t), valueSelector.invoke(t));
        }
        return map;
    }
    //endregion
}
