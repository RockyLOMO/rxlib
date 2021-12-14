package org.rx.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.exception.InvalidException;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tuple<T1, T2> implements Serializable {
    private static final long serialVersionUID = 5110049327430282262L;

    static class ReadOnlyTuple<T1, T2> extends Tuple<T1, T2> {
        int hash;

        @Override
        public int hashCode() {
            if (hash == 0) {
                hash = super.hashCode();
            }
            return hash;
        }

        @Override
        public void setLeft(T1 left) {
            throw new InvalidException("unmodifiable");
        }

        @Override
        public void setRight(T2 right) {
            throw new InvalidException("unmodifiable");
        }

        public ReadOnlyTuple(T1 t1, T2 t2) {
            super(t1, t2);
        }
    }

    public static <T1, T2> Tuple<T1, T2> of(T1 t1, T2 t2) {
        return new Tuple<>(t1, t2);
    }

    //make sure hashcode of t1 & t2 won't be changed.
    public static <T1, T2> Tuple<T1, T2> unsafeReadOnly(T1 t1, T2 t2) {
        return new ReadOnlyTuple<>(t1, t2);
    }

    public T1 left;
    public T2 right;
}
