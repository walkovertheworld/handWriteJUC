package org.example.juctest;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * 迭代版本4(终版)：park/unpark ＋ unsafe类 CAS 实现无锁 FutureTask，解决并发问题
 * @param <V>
 */
@Slf4j
public class MyFutureTask4<V> implements Runnable, Future {
    /**
     * 维护等待链表的头部。
     */
    private volatile WaitNode waiterHead;

    @Getter
    private volatile V result;

    private final Callable<V> callable;


    private static final sun.misc.Unsafe UNSAFE;

    private static final long waiterHeadOffset;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
            waiterHeadOffset = UNSAFE.objectFieldOffset
                    (MyFutureTask4.class.getDeclaredField("waiterHead"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public MyFutureTask4(Callable<V> callable) {
        this.callable = callable;
    }

    class WaitNode{
        private volatile Thread thread;
        private volatile WaitNode next;
        public WaitNode(Thread thread) {
            this.thread = thread;
        }
    }

    @Override
    public void run() {
        try {
            this.result  = callable.call();
            WaitNode node = this.waiterHead;
            while (node != null) {
                LockSupport.unpark(node.thread);
                node = node.next;
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
        return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long waitNanos = unit.toNanos(timeout);
        final long deadline = System.nanoTime() + waitNanos;
        boolean queued = false;
        while (true) {
            if (result != null) {
                return result;
            }
            if (!queued) {
                WaitNode current = new WaitNode(Thread.currentThread());
                WaitNode head = this.waiterHead;
                current.next = head;
                boolean isCASSuccess = UNSAFE.compareAndSwapObject(this, waiterHeadOffset, head, current);
                if (!isCASSuccess) {
                    continue;
                }
                queued = true;
                continue;
            }

            long remainTime =  deadline - System.nanoTime();
            if (remainTime <= 0L) {
                throw new TimeoutException();
            } else {
                LockSupport.parkNanos(this, remainTime);
            }
        }

    }


    /**
     * 模拟异常情况：调用 future.get时刚好任务线程完成，是否导致线程安全问题
     */
    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            MyFutureTask4<String> futureTask = new MyFutureTask4<>(() -> {
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



    static void waitFutureTask(MyFutureTask4<String> futureTask, int waitSeconds) {
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

