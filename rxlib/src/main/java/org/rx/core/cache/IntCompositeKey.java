//package org.rx.core.cache;
//
//import lombok.AllArgsConstructor;
//import lombok.Getter;
//
//import java.util.Objects;
//
//@AllArgsConstructor
//@Getter
//public class IntCompositeKey<T> {
//    short region;
//    T key;
//
//    @Override
//    public boolean equals(Object o) {
//        if (o == null || getClass() != o.getClass()) return false;
//        IntCompositeKey<?> that = (IntCompositeKey<?>) o;
//        return region == that.region && Objects.equals(key, that.key);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(region, key);
//    }
//}
