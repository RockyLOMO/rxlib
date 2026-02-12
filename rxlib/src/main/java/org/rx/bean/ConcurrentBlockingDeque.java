package org.rx.bean;

import lombok.NonNull;

import java.util.*;
import java.util.concurrent.*;

public class ConcurrentBlockingDeque<E> extends AbstractQueue<E>
        implements BlockingDeque<E>, java.io.Serializable {
    private static final long serialVersionUID = -1456885974056230873L;

    private final ConcurrentLinkedDeque<E> deque;
    private final Semaphore items;
    private final Semaphore spaces;

    public ConcurrentBlockingDeque() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentBlockingDeque(int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        this.deque = new ConcurrentLinkedDeque<>();
        this.items = new Semaphore(0);
        this.spaces = new Semaphore(capacity);
    }

    public ConcurrentBlockingDeque(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        addAll(c);
    }

    // Basic Queue/Deque methods
    @Override
    public int size() {
        return items.availablePermits();
    }

    @Override
    public int remainingCapacity() {
        return spaces.availablePermits();
    }

    // Adding

    @Override
    public void addFirst(@NonNull E e) {
        if (!offerFirst(e))
            throw new IllegalStateException("Deque full");
    }

    @Override
    public void addLast(@NonNull E e) {
        if (!offerLast(e))
            throw new IllegalStateException("Deque full");
    }

    @Override
    public boolean offerFirst(@NonNull E e) {
        if (spaces.tryAcquire()) {
            deque.offerFirst(e);
            items.release();
            return true;
        }
        return false;
    }

    @Override
    public boolean offerLast(@NonNull E e) {
        if (spaces.tryAcquire()) {
            deque.offerLast(e);
            items.release();
            return true;
        }
        return false;
    }

    @Override
    public void putFirst(@NonNull E e) throws InterruptedException {
        spaces.acquire();
        deque.offerFirst(e);
        items.release();
    }

    @Override
    public void putLast(@NonNull E e) throws InterruptedException {
        spaces.acquire();
        deque.offerLast(e);
        items.release();
    }

    @Override
    public boolean offerFirst(@NonNull E e, long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        if (spaces.tryAcquire(timeout, unit)) {
            deque.offerFirst(e);
            items.release();
            return true;
        }
        return false;
    }

    @Override
    public boolean offerLast(@NonNull E e, long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        if (spaces.tryAcquire(timeout, unit)) {
            deque.offerLast(e);
            items.release();
            return true;
        }
        return false;
    }

    // Removing

    @Override
    public E takeFirst() throws InterruptedException {
        items.acquire();
        E x = deque.pollFirst();
        if (x != null) {
            spaces.release();
            return x;
        }
        return takeFirst(); // Retry
    }

    @Override
    public E takeLast() throws InterruptedException {
        items.acquire();
        E x = deque.pollLast();
        if (x != null) {
            spaces.release();
            return x;
        }
        return takeLast(); // Retry
    }

    @Override
    public E pollFirst(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long start = System.nanoTime();
        while (true) {
            if (items.tryAcquire(nanos, TimeUnit.NANOSECONDS)) {
                E x = deque.pollFirst();
                if (x != null) {
                    spaces.release();
                    return x;
                }
                long elapsed = System.nanoTime() - start;
                nanos -= elapsed;
                start = System.nanoTime();
                if (nanos <= 0)
                    return null;
                continue;
            }
            return null;
        }
    }

    @Override
    public E pollLast(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long start = System.nanoTime();
        while (true) {
            if (items.tryAcquire(nanos, TimeUnit.NANOSECONDS)) {
                E x = deque.pollLast();
                if (x != null) {
                    spaces.release();
                    return x;
                }
                long elapsed = System.nanoTime() - start;
                nanos -= elapsed;
                start = System.nanoTime();
                if (nanos <= 0)
                    return null;
                continue;
            }
            return null;
        }
    }

    @Override
    public E removeFirst() {
        E x = pollFirst();
        if (x == null)
            throw new NoSuchElementException();
        return x;
    }

    @Override
    public E removeLast() {
        E x = pollLast();
        if (x == null)
            throw new NoSuchElementException();
        return x;
    }

    @Override
    public E pollFirst() {
        if (items.tryAcquire()) {
            E x = deque.pollFirst();
            if (x != null) {
                spaces.release();
                return x;
            }
            // item stolen
            return null;
        }
        return null;
    }

    @Override
    public E pollLast() {
        if (items.tryAcquire()) {
            E x = deque.pollLast();
            if (x != null) {
                spaces.release();
                return x;
            }
            return null;
        }
        return null;
    }

    // Inspection

    @Override
    public E getFirst() {
        E x = peekFirst();
        if (x == null)
            throw new NoSuchElementException();
        return x;
    }

    @Override
    public E getLast() {
        E x = peekLast();
        if (x == null)
            throw new NoSuchElementException();
        return x;
    }

    @Override
    public E peekFirst() {
        return deque.peekFirst();
    }

    @Override
    public E peekLast() {
        return deque.peekLast();
    }

    // Stack methods

    @Override
    public void push(@NonNull E e) {
        addFirst(e);
    }

    @Override
    public E pop() {
        return removeFirst();
    }

    // Removal (General)

    @Override
    public boolean remove(Object o) {
        if (o == null)
            return false;
        boolean removed = deque.remove(o);
        if (removed) {
            spaces.release();
            if (!items.tryAcquire()) {
                // We removed an element but couldn't decrement items count.
                // This implies a concurrent taker has already acquired the permit
                // but hasn't polled the element yet.
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removeFirstOccurrence(Object o) {
        if (o == null)
            return false;
        boolean removed = deque.removeFirstOccurrence(o);
        if (removed) {
            spaces.release();
            if (!items.tryAcquire()) {
                // race handled as in remove(o)
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removeLastOccurrence(Object o) {
        if (o == null)
            return false;
        boolean removed = deque.removeLastOccurrence(o);
        if (removed) {
            spaces.release();
            if (!items.tryAcquire()) {
                // race handled as in remove(o)
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return deque.contains(o);
    }

    @Override
    public Iterator<E> iterator() {
        return deque.iterator();
    }

    @Override
    public Iterator<E> descendingIterator() {
        return deque.descendingIterator();
    }

    // AbstractQueue overrides

    @Override
    public E element() {
        return getFirst();
    }

    @Override
    public E peek() {
        return peekFirst();
    }

    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    @Override
    public E poll() {
        return pollFirst();
    }

    @Override
    public void put(E e) throws InterruptedException {
        putLast(e);
    }

    @Override
    public E take() throws InterruptedException {
        return takeFirst();
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return offerLast(e, timeout, unit);
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pollFirst(timeout, unit);
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;

        int n = 0;
        while (n < maxElements) {
            E e = pollFirst();
            if (e == null)
                break;
            c.add(e);
            n++;
        }
        return n;
    }
}
