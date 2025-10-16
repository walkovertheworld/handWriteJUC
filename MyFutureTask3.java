package org.example.juctest;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.*;

/**
 * 迭代版本3：wait/notifyAll 依赖锁实现 FutureTask，线程安全，但有锁，影响性能
 * @param <V>
 */
@Slf4j
public class MyFutureTask3 <V> implements Runnable, Future {

    @Getter
    private volatile V result;

    private final Callable<V> callable;

    public MyFutureTask3(Callable<V> callable) {
        this.callable = callable;
    }

    @Override
    public void run() {
        try {
            this.result  = callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        synchronized (this) {
            this.notifyAll();
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
        if (result != null) {
            return result;
        }
        // 串行入队并阻塞
        synchronized (this) {
            if (result != null) {
                return result;
            }
            this.wait();
            return result;
        }
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException {
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (result != null) {
                return result;
            }
            this.wait(unit.toMillis(timeout));
            // 判断是任务完成正常唤醒还是超时(此处暂不处理虚假唤醒，后面会讲)
            if (result != null) {
                return result;
            }
            throw new TimeoutException();
        }

    }

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            MyFutureTask3<String> futureTask = new MyFutureTask3<>(() -> {
                log.info("线程A启动,执行任务中...");
                Thread.sleep(1000);
                log.info("线程A任务执行完成...");
                return "hello, FutureTask!";
            });
            new Thread(futureTask, "A").start();


            Thread.sleep(999);
            int waitSeconds = 2;
            for (int j = 0; j < 5; j++) {
                new Thread(() -> {
                    waitFutureTask(futureTask, waitSeconds);
                }, "t" + j).start();
            }
            waitFutureTask(futureTask, waitSeconds);
            Thread.sleep(100);
        }
    }

    static void waitFutureTask(MyFutureTask3<String> futureTask, int waitSeconds) {
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
