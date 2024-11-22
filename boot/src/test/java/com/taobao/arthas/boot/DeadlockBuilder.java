package com.taobao.arthas.boot;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 实现死锁
 */
public class DeadlockBuilder {
    private static final ReentrantLock REENTRANT_LOCK_A = new ReentrantLock();
    private static final ReentrantLock REENTRANT_LOCK_B = new ReentrantLock();
    public static void deadlockByMonitor() {
        Object a = new Object();
        Object b = new Object();
        Thread threadA = new Thread(() -> {
            synchronized (a) {
                sleep();
                synchronized (b) {
                }
            }
        });
        threadA.setName("Thread_Monitor_A");
        threadA.start();
        Thread threadB = new Thread(() -> {
            synchronized (b) {
                sleep();
                synchronized (a) {
                }
            }
        });
        threadB.setName("Thread_Monitor_B");
        threadB.start();
    }
    public static void deadlockBySynchronizer() {
        Thread threadA = new Thread(() -> {
            REENTRANT_LOCK_A.lock();
            try {
                sleep();
                REENTRANT_LOCK_B.lock();
                try {
                } finally {
                    REENTRANT_LOCK_B.unlock();
                }
            } finally {
                REENTRANT_LOCK_A.unlock();
            }
        });
        threadA.setName("Thread_Synchronizer_A");
        threadA.start();
        Thread threadB = new Thread(() -> {
            REENTRANT_LOCK_B.lock();
            try {
                sleep();
                REENTRANT_LOCK_A.lock();
                try {
                } finally {
                    REENTRANT_LOCK_A.unlock();
                }
            } finally {
                REENTRANT_LOCK_B.unlock();
            }
        });
        threadB.setName("Thread_Synchronizer_B");
        threadB.start();
    }
    private static void sleep() {
        try {
            Thread.sleep(100L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        DeadlockBuilder.deadlockByMonitor();
        DeadlockBuilder.deadlockBySynchronizer();
    }
}
