package org.example.juctest;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 迭代版本2：park/unpark无锁实现的初版，但存在各种并发问题
 * @param <V>
 */
@Slf4j
public class MyFutureTask2<V> implements Runnable, Future {

    @Getter
    private volatile V result;

    private final Callable<V> callable;

    private final Vector<Thread> waitThreads = new Vector<>();

    public MyFutureTask2(Callable<V> callable) {
        this.callable = callable;
    }

    @Override
    public void run() {
        try {
            this.result  = callable.call();
            for (Thread waitThread : waitThreads) {
                LockSupport.unpark(waitThread);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
        // 如果 FutureTask 尚未完成则阻塞，并将线程加入队列，等待Task完成时将其唤醒。
        if (result == null) {
            waitThreads.add(Thread.currentThread());
            LockSupport.park();
            waitThreads.remove(Thread.currentThread());
            return result;
        }
        return result;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long waitNanos = unit.toNanos(timeout);
        if (result == null) {
            waitThreads.add(Thread.currentThread());
            LockSupport.parkNanos(waitNanos);
            waitThreads.remove(Thread.currentThread());
            if (result == null) {
                throw new TimeoutException();
            }
            return result;
        }
        return result;
    }


//    /**
//     * 正常情况
//     * @param args
//     * @throws InterruptedException
//     */
//    public static void main(String[] args) throws InterruptedException {
//        MyFutureTask2<String> futureTask = new MyFutureTask2<>(() -> {
//            log.info("线程A启动,执行任务中...");
//            Thread.sleep(2000);
//            log.info("线程A任务执行完成...");
//            return "hello, FutureTask!";
//        });
//        new Thread(futureTask, "A").start();
//
//
//        int waitSeconds = 3;
//        new Thread(()->{
//            waitFutureTask(futureTask, waitSeconds);
//        }, "B").start();
//
//        waitFutureTask(futureTask, waitSeconds);
//    }


    /**
     * 模拟异常情况1：调用 future.get时刚好任务线程完成，导致线程安全问题
     */
    public static void main(String[] args) throws InterruptedException {

        for (int i = 0; i < 100; i++) {
            ReentrantLock lock = new ReentrantLock();
            new Thread(()->{
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                lock.lock();

            });


            MyFutureTask2<String> futureTask = new MyFutureTask2<>(() -> {
                log.info("线程A启动,执行任务中...");
                Thread.sleep(1000);
                log.info("线程A任务执行完成...");
                return "hello, FutureTask!";
            });
            new Thread(futureTask, "A").start();


            Thread.sleep(999);
            int waitSeconds = 2;
            List<Thread> threads = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                Thread thread = new Thread(() -> {
                    waitFutureTask(futureTask, waitSeconds);
                }, "t" + j);
                thread.start();
                threads.add(thread);
            }
            waitFutureTask(futureTask, waitSeconds);
            Thread.sleep(500);
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    throw new RuntimeException("错误");
                }
            }
        }
    }


//    /**
//     * 模拟异常情况2：线程调用 future.get，在超时返回的同时任务执行完成。
//     */
//    public static void main(String[] args) throws InterruptedException {
//        for (int i = 0; i < 100; i++) {
//            MyFutureTask2<String> futureTask = new MyFutureTask2<>(() -> {
//                log.info("线程A启动,执行任务中...");
//                Thread.sleep(1000);
//                log.info("线程A任务执行完成...");
//                return "hello, FutureTask!";
//            });
//            new Thread(futureTask, "A").start();
//
//
//            Thread.sleep(999);
//            int waitSeconds = 2;
//            for (int j = 0; j < 5; j++) {
//                new Thread(() -> {
//                    waitFutureTask(futureTask, waitSeconds);
//                }, "t" + j).start();
//            }
//            waitFutureTask(futureTask, waitSeconds);
//            Thread.sleep(100);
//        }
//    }


    static void waitFutureTask(MyFutureTask2<String> futureTask, int waitSeconds) {
        log.info("线程{}等待 futureTask 完成...",  Thread.currentThread().getName());
        long start = System.currentTimeMillis();
        try {
            String futureTaskResult = (String)futureTask.get(waitSeconds, TimeUnit.SECONDS);
            log.info("线程{}等待{} ms 后，任务正常完成，返回值：{}",Thread.currentThread().getName(),
                    System.currentTimeMillis() - start, futureTaskResult);
        } catch (ExecutionException e) {
            log.error("线程{}等待{} ms 后，futureTask 任务内部执行错误,错误信息:{}",Thread.currentThread().getName(),
                    System.currentTimeMillis() - start, e.getMessage());
        } catch (TimeoutException e) {
            log.warn("线程{}等待{} ms 后, futureTask 仍未完成，任务超时", Thread.currentThread().getName(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
