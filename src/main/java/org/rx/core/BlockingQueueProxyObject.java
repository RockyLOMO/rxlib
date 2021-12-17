//package org.rx.core;
//
//import lombok.RequiredArgsConstructor;
//
//import java.util.Collection;
//import java.util.Iterator;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.TimeUnit;
//
//@RequiredArgsConstructor
//class BlockingQueueProxyObject<T> implements BlockingQueue<T> {
//    final BlockingQueue<T> raw;
//
//    @Override
//    public boolean add(T t) {
//        return raw.add(t);
//    }
//
//    @Override
//    public boolean offer(T t) {
//        return raw.offer(t);
//    }
//
//    @Override
//    public T remove() {
//        return raw.remove();
//    }
//
//    @Override
//    public T poll() {
//        return raw.poll();
//    }
//
//    @Override
//    public T element() {
//        return raw.element();
//    }
//
//    @Override
//    public T peek() {
//        return raw.peek();
//    }
//
//    @Override
//    public void put(T t) throws InterruptedException {
//        raw.put(t);
//    }
//
//    @Override
//    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
//        return raw.offer(t, timeout, unit);
//    }
//
//    @Override
//    public T take() throws InterruptedException {
//        return raw.take();
//    }
//
//    @Override
//    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
//        return raw.poll(timeout, unit);
//    }
//
//    @Override
//    public int remainingCapacity() {
//        return raw.remainingCapacity();
//    }
//
//    @Override
//    public boolean remove(Object o) {
//        return raw.remove(o);
//    }
//
//    @Override
//    public boolean containsAll(Collection<?> c) {
//        return raw.containsAll(c);
//    }
//
//    @Override
//    public boolean addAll(Collection<? extends T> c) {
//        return raw.addAll(c);
//    }
//
//    @Override
//    public boolean removeAll(Collection<?> c) {
//        return raw.removeAll(c);
//    }
//
//    @Override
//    public boolean retainAll(Collection<?> c) {
//        return raw.retainAll(c);
//    }
//
//    @Override
//    public void clear() {
//        raw.clear();
//    }
//
//    @Override
//    public int size() {
//        return raw.size();
//    }
//
//    @Override
//    public boolean isEmpty() {
//        return raw.isEmpty();
//    }
//
//    @Override
//    public boolean contains(Object o) {
//        return raw.contains(o);
//    }
//
//    @Override
//    public Iterator<T> iterator() {
//        return raw.iterator();
//    }
//
//    @Override
//    public Object[] toArray() {
//        return raw.toArray();
//    }
//
//    @Override
//    public <T1> T1[] toArray(T1[] a) {
//        return raw.toArray(a);
//    }
//
//    @Override
//    public int drainTo(Collection<? super T> c) {
//        return raw.drainTo(c);
//    }
//
//    @Override
//    public int drainTo(Collection<? super T> c, int maxElements) {
//        return raw.drainTo(c, maxElements);
//    }
//}
