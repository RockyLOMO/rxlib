/*
 * Copyright (C) 2008 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.rx.util.thrid;

import java.io.Serializable;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;

/**
 * A function from {@code A} to {@code B} with an associated <i>reverse</i> function from {@code B}
 * to {@code A}; used for converting back and forth between <i>different representations of the same
 * information</i>.
 *
 * <h3>Invertibility</h3>
 *
 * <p>The reverse operation <b>may</b> be a strict <i>inverse</i> (meaning that {@code
 * converter.reverse().convert(converter.convert(a)).equals(a)} is always true). However, it is very
 * common (perhaps <i>more</i> common) for round-trip conversion to be <i>lossy</i>. Consider an
 * example round-trip using {@link com.google.common.primitives.Doubles#stringConverter}:
 *
 * <ol>
 *   <li>{@code stringConverter().convert("1.00")} returns the {@code Double} value {@code 1.0}
 *   <li>{@code stringConverter().reverse().convert(1.0)} returns the string {@code "1.0"} --
 *       <i>not</i> the same string ({@code "1.00"}) we started with
 * </ol>
 *
 * <p>Note that it should still be the case that the round-tripped and original objects are
 * <i>similar</i>.
 *
 * <h3>Nullability</h3>
 *
 * <p>A converter always converts {@code null} to {@code null} and non-null references to non-null
 * references. It would not make sense to consider {@code null} and a non-null reference to be
 * "different representations of the same information", since one is distinguishable from
 * <i>missing</i> information and the other is not. The {@link #convert} method handles this null
 * behavior for all converters; implementations of {@link #doForward} and {@link #doBackward} are
 * guaranteed to never be passed {@code null}, and must never return {@code null}.
 *
 * <h3>Common ways to use</h3>
 *
 * <p>Using a converter:
 *
 * <ul>
 *   <li>Convert one instance in the "forward" direction using {@code converter.convert(a)}.
 *   <li>Convert multiple instances "forward" using {@code converter.convertAll(as)}.
 *   <li>Convert in the "backward" direction using {@code converter.reverse().convert(b)} or {@code
 *       converter.reverse().convertAll(bs)}.
 *   <li>Use {@code converter} or {@code converter.reverse()} anywhere a {@link
 *       java.util.function.Function} is accepted (for example {@link java.util.stream.Stream#map
 *       Stream.map}).
 *   <li><b>Do not</b> call {@link #doForward} or {@link #doBackward} directly; these exist only to
 *       be overridden.
 * </ul>
 *
 * <h3>Example</h3>
 *
 * <pre>
 *   return new Converter&lt;Integer, String&gt;() {
 *     protected String doForward(Integer i) {
 *       return Integer.toHexString(i);
 *     }
 *
 *     protected Integer doBackward(String s) {
 *       return parseUnsignedInt(s, 16);
 *     }
 *   };</pre>
 *
 * <p>An alternative using Java 8:
 *
 * <pre>{@code
 * return Converter.from(
 *     Integer::toHexString,
 *     s -> parseUnsignedInt(s, 16));
 * }</pre>
 *
 * @author Mike Ward
 * @author Kurt Alfred Kluever
 * @author Gregory Kick
 * @since 16.0
 */
/*
 * 1. The type parameter is <T> rather than <T extends @Nullable> so that we can use T in the
 * doForward and doBackward methods to indicate that the parameter cannot be null. (We also take
 * advantage of that for convertAll, as discussed on that method.)
 *
 * 2. The supertype of this class could be `Function<@Nullable A, @Nullable B>`, since
 * Converter.apply (like Converter.convert) is capable of accepting null inputs. However, a
 * supertype of `Function<A, B>` turns out to be massively more useful to callers in practice: They
 * want their output to be non-null in operations like `stream.map(myConverter)`, and we can
 * guarantee that as long as we also require the input type to be non-null[*] (which is a
 * requirement that existing callers already fulfill).
 *
 * Disclaimer: Part of the reason that callers are so well adapted to `Function<A, B>` may be that
 * that is how the signature looked even prior to this comment! So naturally any change can break
 * existing users, but it can't *fix* existing users because any users who needed
 * `Function<@Nullable A, @Nullable B>` already had to find a workaround. Still, there is a *ton* of
 * fallout from trying to switch. I would be shocked if the switch would offer benefits to anywhere
 * near enough users to justify the costs.
 *
 * Fortunately, if anyone does want to use a Converter as a `Function<@Nullable A, @Nullable B>`,
 * it's easy to get one: `converter::convert`.
 *
 * [*] In annotating this class, we're ignoring LegacyConverter.
 */
public abstract class Converter<A, B> implements Function<A, B> {
    private final boolean handleNullAutomatically;

    private transient Converter<B, A> reverse;

    /**
     * Constructor for use by subclasses.
     */
    protected Converter() {
        this(true);
    }

    /**
     * Constructor used only by {@code LegacyConverter} to suspend automatic null-handling.
     */
    Converter(boolean handleNullAutomatically) {
        this.handleNullAutomatically = handleNullAutomatically;
    }

    // SPI methods (what subclasses must implement)

    /**
     * Returns a representation of {@code a} as an instance of type {@code B}. If {@code a} cannot be
     * converted, an unchecked exception (such as {@link IllegalArgumentException}) should be thrown.
     *
     * @param a the instance to convert; will never be null
     * @return the converted instance; <b>must not</b> be null
     */
    protected abstract B doForward(A a);

    /**
     * Returns a representation of {@code b} as an instance of type {@code A}. If {@code b} cannot be
     * converted, an unchecked exception (such as {@link IllegalArgumentException}) should be thrown.
     *
     * @param b the instance to convert; will never be null
     * @return the converted instance; <b>must not</b> be null
     * @throws UnsupportedOperationException if backward conversion is not implemented; this should be
     *                                       very rare. Note that if backward conversion is not only unimplemented but
     *                                       unimplement<i>able</i> (for example, consider a {@code Converter<Chicken, ChickenNugget>}),
     *                                       then this is not logically a {@code Converter} at all, and should just implement {@link
     *                                       Function}.
     */
    protected abstract A doBackward(B b);

    // API (consumer-side) methods

    /**
     * Returns a representation of {@code a} as an instance of type {@code B}.
     *
     * @return the converted value; is null <i>if and only if</i> {@code a} is null
     */
    public final B convert(A a) {
        return correctedDoForward(a);
    }

    B correctedDoForward(A a) {
        if (handleNullAutomatically) {
            // TODO(kevinb): we shouldn't be checking for a null result at runtime. Assert?
            return a == null ? null : Objects.requireNonNull(doForward(a));
        } else {
            return unsafeDoForward(a);
        }
    }

    A correctedDoBackward(B b) {
        if (handleNullAutomatically) {
            // TODO(kevinb): we shouldn't be checking for a null result at runtime. Assert?
            return b == null ? null : Objects.requireNonNull(doBackward(b));
        } else {
            return unsafeDoBackward(b);
        }
    }

    /*
     * LegacyConverter violates the contract of Converter by allowing its doForward and doBackward
     * methods to accept null. We could avoid having unchecked casts in Converter.java itself if we
     * could perform a cast to LegacyConverter, but we can't because it's an internal-only class.
     *
     * TODO(cpovirk): So make it part of the open-source build, albeit package-private there?
     *
     * So we use uncheckedCastNullableTToT here. This is a weird usage of that method: The method is
     * documented as being for use with type parameters that have parametric nullness. But Converter's
     * type parameters do not. Still, we use it here so that we can suppress a warning at a smaller
     * level than the whole method but without performing a runtime null check. That way, we can still
     * pass null inputs to LegacyConverter, and it can violate the contract of Converter.
     *
     * TODO(cpovirk): Could this be simplified if we modified implementations of LegacyConverter to
     * override methods (probably called "unsafeDoForward" and "unsafeDoBackward") with the same
     * signatures as the methods below, rather than overriding the same doForward and doBackward
     * methods as implementations of normal converters do?
     *
     * But no matter what we do, it's worth remembering that the resulting code is going to be unsound
     * in the presence of LegacyConverter, at least in the case of users who view the converter as a
     * Function<A, B> or who call convertAll (and for any checkers that apply @PolyNull-like semantics
     * to Converter.convert). So maybe we don't want to think too hard about how to prevent our
     * checkers from issuing errors related to LegacyConverter, since it turns out that
     * LegacyConverter does violate the assumptions we make elsewhere.
     */


    private B unsafeDoForward(A a) {
        return doForward(a);
    }


    private A unsafeDoBackward(B b) {
        return doBackward(b);
    }

    /**
     * Returns an iterable that applies {@code convert} to each element of {@code fromIterable}. The
     * conversion is done lazily.
     *
     * <p>The returned iterable's iterator supports {@code remove()} if the input iterator does. After
     * a successful {@code remove()} call, {@code fromIterable} no longer contains the corresponding
     * element.
     */
    /*
     * Just as Converter could implement `Function<@Nullable A, @Nullable B>` instead of `Function<A,
     * B>`, convertAll could accept and return iterables with nullable element types. In both cases,
     * we've chosen to instead use a signature that benefits existing users -- and is still safe.
     *
     * For convertAll, I haven't looked as closely at *how* much existing users benefit, so we should
     * keep an eye out for problems that new users encounter. Note also that convertAll could support
     * both use cases by using @PolyNull. (By contrast, we can't use @PolyNull for our superinterface
     * (`implements Function<@PolyNull A, @PolyNull B>`), at least as far as I know.)
     */
    public Iterable<B> convertAll(Iterable<? extends A> fromIterable) {
        Objects.requireNonNull(fromIterable, "fromIterable");
        return new Iterable<B>() {
            @Override
            public Iterator<B> iterator() {
                return new Iterator<B>() {
                    private final Iterator<? extends A> fromIterator = fromIterable.iterator();

                    @Override
                    public boolean hasNext() {
                        return fromIterator.hasNext();
                    }

                    @Override
                    @SuppressWarnings("nullness") // See code comments on convertAll and Converter.apply.

                    public B next() {
                        return convert(fromIterator.next());
                    }

                    @Override
                    public void remove() {
                        fromIterator.remove();
                    }
                };
            }
        };
    }

    /**
     * Returns the reversed view of this converter, which converts {@code this.convert(a)} back to a
     * value roughly equivalent to {@code a}.
     *
     * <p>The returned converter is serializable if {@code this} converter is.
     *
     * <p><b>Note:</b> you should not override this method. It is non-final for legacy reasons.
     */
    public Converter<B, A> reverse() {
        Converter<B, A> result = reverse;
        return (result == null) ? reverse = new ReverseConverter<>(this) : result;
    }

    private static final class ReverseConverter<A, B> extends Converter<B, A>
            implements Serializable {
        final Converter<A, B> original;

        ReverseConverter(Converter<A, B> original) {
            this.original = original;
        }

        /*
         * These gymnastics are a little confusing. Basically this class has neither legacy nor
         * non-legacy behavior; it just needs to let the behavior of the backing converter shine
         * through. So, we override the correctedDo* methods, after which the do* methods should never
         * be reached.
         */

        @Override
        protected A doForward(B b) {
            throw new AssertionError();
        }

        @Override
        protected B doBackward(A a) {
            throw new AssertionError();
        }

        @Override
        A correctedDoForward(B b) {
            return original.correctedDoBackward(b);
        }

        @Override
        B correctedDoBackward(A a) {
            return original.correctedDoForward(a);
        }

        @Override
        public Converter<A, B> reverse() {
            return original;
        }

        @Override
        public boolean equals(Object object) {
            if (object instanceof ReverseConverter) {
                ReverseConverter<?, ?> that = (ReverseConverter<?, ?>) object;
                return this.original.equals(that.original);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return ~original.hashCode();
        }

        @Override
        public String toString() {
            return original + ".reverse()";
        }

        private static final long serialVersionUID = 0L;
    }

    /**
     * Returns a converter whose {@code convert} method applies {@code secondConverter} to the result
     * of this converter. Its {@code reverse} method applies the converters in reverse order.
     *
     * <p>The returned converter is serializable if {@code this} converter and {@code secondConverter}
     * are.
     */
    public final <C> Converter<A, C> andThen(Converter<B, C> secondConverter) {
        return doAndThen(secondConverter);
    }

    /**
     * Package-private non-final implementation of andThen() so only we can override it.
     */
    <C> Converter<A, C> doAndThen(Converter<B, C> secondConverter) {
        return new ConverterComposition<>(this, Objects.requireNonNull(secondConverter));
    }

    private static final class ConverterComposition<A, B, C> extends Converter<A, C>
            implements Serializable {
        final Converter<A, B> first;
        final Converter<B, C> second;

        ConverterComposition(Converter<A, B> first, Converter<B, C> second) {
            this.first = first;
            this.second = second;
        }

        /*
         * These gymnastics are a little confusing. Basically this class has neither legacy nor
         * non-legacy behavior; it just needs to let the behaviors of the backing converters shine
         * through (which might even differ from each other!). So, we override the correctedDo* methods,
         * after which the do* methods should never be reached.
         */

        @Override
        protected C doForward(A a) {
            throw new AssertionError();
        }

        @Override
        protected A doBackward(C c) {
            throw new AssertionError();
        }

        @Override
        C correctedDoForward(A a) {
            return second.correctedDoForward(first.correctedDoForward(a));
        }

        @Override
        A correctedDoBackward(C c) {
            return first.correctedDoBackward(second.correctedDoBackward(c));
        }

        @Override
        public boolean equals(Object object) {
            if (object instanceof ConverterComposition) {
                ConverterComposition<?, ?, ?> that = (ConverterComposition<?, ?, ?>) object;
                return this.first.equals(that.first) && this.second.equals(that.second);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * first.hashCode() + second.hashCode();
        }

        @Override
        public String toString() {
            return first + ".andThen(" + second + ")";
        }

        private static final long serialVersionUID = 0L;
    }

    /**
     * @deprecated Provided to satisfy the {@code Function} interface; use {@link #convert} instead.
     */
    @Deprecated
    @Override
    /*
     * Even though we implement `Function<A, B>` instead of `Function<@Nullable A, @Nullable B>` (as
     * discussed in a code comment at the top of the class), we declare our override of Function.apply
     * to accept and return null. This requires a suppression, but it's safe:
     *
     * - Callers who use Converter as a Function<A, B> will neither pass null nor have it returned to
     *   them. (Or, if they're not using nullness checking, they might be able to pass null and thus
     *   have null returned to them. But our signature isn't making their existing nullness type error
     *   any worse.)
     * - In the relatively unlikely event that anyone calls Converter.apply directly, that caller is
     *   allowed to pass null but is also forced to deal with a potentially null return.
     * - Perhaps more important than actual *callers* of this method are various tools that look at
     *   bytecode. Notably, NullPointerTester expects a method to throw NPE when passed null unless it
     *   is annotated in a way that identifies its parameter type as potentially including null. (And
     *   this method does not throw NPE -- nor do we want to enact a dangerous change to make it begin
     *   doing so.) We can even imagine tools that rewrite bytecode to insert null checks before and
     *   after calling methods with allegedly non-nullable parameters[*]. If we didn't annotate the
     *   parameter and return type here, then anyone who used such a tool (and managed to pass null to
     *   this method, presumably because that user doesn't run a normal nullness checker) could see
     *   NullPointerException.
     *
     * [*] Granted, such tools could conceivably be smart enough to recognize that the apply() method
     * on a a Function<Foo, Bar> should never allow null inputs and never produce null outputs even if
     * this specific subclass claims otherwise. Such tools might still produce NPE for calls to this
     * method. And that is one reason that we should be nervous about "lying" by extending Function<A,
     * B> in the first place. But for now, we're giving it a try, since extending Function<@Nullable
     * A, @Nullable B> will cause issues *today*, whereas extending Function<A, B> causes problems in
     * various hypothetical futures. (Plus, a tool that were that smart would likely already introduce
     * problems with LegacyConverter.)
     */
    @SuppressWarnings("nullness")

    public final B apply(A a) {
        return convert(a);
    }

    /**
     * Indicates whether another object is equal to this converter.
     *
     * <p>Most implementations will have no reason to override the behavior of {@link Object#equals}.
     * However, an implementation may also choose to return {@code true} whenever {@code object} is a
     * {@link Converter} that it considers <i>interchangeable</i> with this one. "Interchangeable"
     * <i>typically</i> means that {@code Objects.equal(this.convert(a), that.convert(a))} is true for
     * all {@code a} of type {@code A} (and similarly for {@code reverse}). Note that a {@code false}
     * result from this method does not imply that the converters are known <i>not</i> to be
     * interchangeable.
     */
    @Override
    public boolean equals(Object object) {
        return super.equals(object);
    }

    // Static converters

    /**
     * Returns a converter based on separate forward and backward functions. This is useful if the
     * function instances already exist, or so that you can supply lambda expressions. If those
     * circumstances don't apply, you probably don't need to use this; subclass {@code Converter} and
     * implement its {@link #doForward} and {@link #doBackward} methods directly.
     *
     * <p>These functions will never be passed {@code null} and must not under any circumstances
     * return {@code null}. If a value cannot be converted, the function should throw an unchecked
     * exception (typically, but not necessarily, {@link IllegalArgumentException}).
     *
     * <p>The returned converter is serializable if both provided functions are.
     *
     * @since 17.0
     */
    public static <A, B> Converter<A, B> from(
            Function<? super A, ? extends B> forwardFunction,
            Function<? super B, ? extends A> backwardFunction) {
        return new FunctionBasedConverter<>(forwardFunction, backwardFunction);
    }

    private static final class FunctionBasedConverter<A, B> extends Converter<A, B>
            implements Serializable {
        private final Function<? super A, ? extends B> forwardFunction;
        private final Function<? super B, ? extends A> backwardFunction;

        private FunctionBasedConverter(
                Function<? super A, ? extends B> forwardFunction,
                Function<? super B, ? extends A> backwardFunction) {
            this.forwardFunction = Objects.requireNonNull(forwardFunction);
            this.backwardFunction = Objects.requireNonNull(backwardFunction);
        }

        @Override
        protected B doForward(A a) {
            return forwardFunction.apply(a);
        }

        @Override
        protected A doBackward(B b) {
            return backwardFunction.apply(b);
        }

        @Override
        public boolean equals(Object object) {
            if (object instanceof FunctionBasedConverter) {
                FunctionBasedConverter<?, ?> that = (FunctionBasedConverter<?, ?>) object;
                return this.forwardFunction.equals(that.forwardFunction)
                        && this.backwardFunction.equals(that.backwardFunction);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return forwardFunction.hashCode() * 31 + backwardFunction.hashCode();
        }

        @Override
        public String toString() {
            return "Converter.from(" + forwardFunction + ", " + backwardFunction + ")";
        }
    }

    /**
     * Returns a serializable converter that always converts or reverses an object to itself.
     */
    @SuppressWarnings("unchecked") // implementation is "fully variant"
    public static <T> Converter<T, T> identity() {
        return (IdentityConverter<T>) IdentityConverter.INSTANCE;
    }

    /**
     * A converter that always converts or reverses an object to itself. Note that T is now a
     * "pass-through type".
     */
    private static final class IdentityConverter<T> extends Converter<T, T> implements Serializable {
        static final IdentityConverter<?> INSTANCE = new IdentityConverter<>();

        @Override
        protected T doForward(T t) {
            return t;
        }

        @Override
        protected T doBackward(T t) {
            return t;
        }

        @Override
        public IdentityConverter<T> reverse() {
            return this;
        }

        @Override
        <S> Converter<T, S> doAndThen(Converter<T, S> otherConverter) {
            return Objects.requireNonNull(otherConverter, "otherConverter");
        }

        /*
         * We *could* override convertAll() to return its input, but it's a rather pointless
         * optimization and opened up a weird type-safety problem.
         */

        @Override
        public String toString() {
            return "Converter.identity()";
        }

        private Object readResolve() {
            return INSTANCE;
        }

        private static final long serialVersionUID = 0L;
    }
}
