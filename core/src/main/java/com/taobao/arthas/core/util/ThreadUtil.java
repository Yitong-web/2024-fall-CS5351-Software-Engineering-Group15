package com.taobao.arthas.core.util;

import com.taobao.arthas.core.command.model.*;
import com.taobao.arthas.core.view.Ansi;

import java.arthas.SpyAPI;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author hengyunabc 2015年12月7日 下午2:29:28
 *
 */
abstract public class ThreadUtil {

    private static final BlockingLockInfo EMPTY_INFO = new BlockingLockInfo();

    private static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private static boolean detectedEagleEye = false;
    public static boolean foundEagleEye = false;

    public static ThreadGroup getRoot() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        ThreadGroup parent;
        while ((parent = group.getParent()) != null) {
            group = parent;
        }
        return group;
    }

    /**
     * 获取所有线程
     */
    public static List<ThreadVO> getThreads() {
        ThreadGroup root = getRoot();
        Thread[] threads = new Thread[root.activeCount()];
        while (root.enumerate(threads, true) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        List<ThreadVO> list = new ArrayList<ThreadVO>(threads.length);
        for (Thread thread : threads) {
            if (thread != null) {
                ThreadVO threadVO = createThreadVO(thread);
                list.add(threadVO);
            }
        }
        return list;
    }

    private static ThreadVO createThreadVO(Thread thread) {
        ThreadGroup group = thread.getThreadGroup();
        ThreadVO threadVO = new ThreadVO();
        threadVO.setId(thread.getId());
        threadVO.setName(thread.getName());
        threadVO.setGroup(group == null ? "" : group.getName());
        threadVO.setPriority(thread.getPriority());
        threadVO.setState(thread.getState());
        threadVO.setInterrupted(thread.isInterrupted());
        threadVO.setDaemon(thread.isDaemon());
        return threadVO;
    }

    /**
     * 获取所有线程List
     *
     * @return
     */
    public static List<Thread> getThreadList() {
        List<Thread> result = new ArrayList<Thread>();
        ThreadGroup root = getRoot();
        Thread[] threads = new Thread[root.activeCount()];
        while (root.enumerate(threads, true) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        for (Thread thread : threads) {
            if (thread != null) {
                result.add(thread);
            }
        }
        return result;
    }


    /**
     * Find the thread and lock that is blocking the most other threads.
     *
     * Time complexity of this algorithm: O(number of thread)
     * Space complexity of this algorithm: O(number of locks)
     *
     * @return the BlockingLockInfo object, or an empty object if not found.
     */
    public static BlockingLockInfo findMostBlockingLock() {
        ThreadInfo[] infos = threadMXBean.dumpAllThreads(threadMXBean.isObjectMonitorUsageSupported(),
                threadMXBean.isSynchronizerUsageSupported());

        // a map of <LockInfo.getIdentityHashCode, number of thread blocking on this>
        Map<Integer, Integer> blockCountPerLock = new HashMap<Integer, Integer>();
        // a map of <LockInfo.getIdentityHashCode, the thread info that holding this lock
        Map<Integer, ThreadInfo> ownerThreadPerLock = new HashMap<Integer, ThreadInfo>();

        for (ThreadInfo info: infos) {
            if (info == null) {
                continue;
            }

            LockInfo lockInfo = info.getLockInfo();
            if (lockInfo != null) {
                // the current thread is blocked waiting on some condition
                if (blockCountPerLock.get(lockInfo.getIdentityHashCode()) == null) {
                    blockCountPerLock.put(lockInfo.getIdentityHashCode(), 0);
                }
                int blockedCount = blockCountPerLock.get(lockInfo.getIdentityHashCode());
                blockCountPerLock.put(lockInfo.getIdentityHashCode(), blockedCount + 1);
            }

            for (MonitorInfo monitorInfo: info.getLockedMonitors()) {
                // the object monitor currently held by this thread
                if (ownerThreadPerLock.get(monitorInfo.getIdentityHashCode()) == null) {
                    ownerThreadPerLock.put(monitorInfo.getIdentityHashCode(), info);
                }
            }

            for (LockInfo lockedSync: info.getLockedSynchronizers()) {
                // the ownable synchronizer currently held by this thread
                if (ownerThreadPerLock.get(lockedSync.getIdentityHashCode()) == null) {
                    ownerThreadPerLock.put(lockedSync.getIdentityHashCode(), info);
                }
            }
        }

        // find the thread that is holding the lock that blocking the largest number of threads.
        int mostBlockingLock = 0; // System.identityHashCode(null) == 0
        int maxBlockingCount = 0;
        for (Map.Entry<Integer, Integer> entry: blockCountPerLock.entrySet()) {
            if (entry.getValue() > maxBlockingCount && ownerThreadPerLock.get(entry.getKey()) != null) {
                // the lock is explicitly held by anther thread.
                maxBlockingCount = entry.getValue();
                mostBlockingLock = entry.getKey();
            }
        }

        if (mostBlockingLock == 0) {
            // nothing found
            return EMPTY_INFO;
        }

        BlockingLockInfo blockingLockInfo = new BlockingLockInfo();
        blockingLockInfo.setThreadInfo(ownerThreadPerLock.get(mostBlockingLock));
        blockingLockInfo.setLockIdentityHashCode(mostBlockingLock);
        blockingLockInfo.setBlockingThreadCount(blockCountPerLock.get(mostBlockingLock));
        return blockingLockInfo;
    }


    public static String getFullStacktrace(ThreadInfo threadInfo) {
        return getFullStacktrace(threadInfo, -1, -1, -1, 0, 0);
    }

    public static String getFullStacktrace(BlockingLockInfo blockingLockInfo) {
        return getFullStacktrace(blockingLockInfo.getThreadInfo(), -1, -1, -1, blockingLockInfo.getLockIdentityHashCode(),
                blockingLockInfo.getBlockingThreadCount());
    }


    /**
     * 完全从 ThreadInfo 中 copy 过来
     * @param threadInfo the thread info object
     * @param cpuUsage will be ignore if cpuUsage < 0 or cpuUsage > 100
     * @param lockIdentityHashCode 阻塞了其他线程的锁的identityHashCode
     * @param blockingThreadCount 阻塞了其他线程的数量
     * @return the string representation of the thread stack
     */
    public static String getFullStacktrace(ThreadInfo threadInfo, double cpuUsage, long deltaTime, long time, int lockIdentityHashCode,
                                           int blockingThreadCount) {
        StringBuilder sb = new StringBuilder("\"" + threadInfo.getThreadName() + "\"" + " Id="
                + threadInfo.getThreadId());

        if (cpuUsage >= 0 && cpuUsage <= 100) {
            sb.append(" cpuUsage=").append(cpuUsage).append("%");
        }
        if (deltaTime >= 0 ) {
            sb.append(" deltaTime=").append(deltaTime).append("ms");
        }
        if (time >= 0 ) {
            sb.append(" time=").append(time).append("ms");
        }

        sb.append(" ").append(threadInfo.getThreadState());

        if (threadInfo.getLockName() != null) {
            sb.append(" on ").append(threadInfo.getLockName());
        }
        if (threadInfo.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(threadInfo.getLockOwnerName()).append("\" Id=").append(threadInfo.getLockOwnerId());
        }
        if (threadInfo.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (threadInfo.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');
        int i = 0;
        for (StackTraceElement ste : threadInfo.getStackTrace()) {
            sb.append("\tat ").append(ste.toString());
            sb.append('\n');
            if (i == 0 && threadInfo.getLockInfo() != null) {
                Thread.State ts = threadInfo.getThreadState();
                switch (ts) {
                    case BLOCKED:
                        sb.append("\t-  blocked on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case WAITING:
                        sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case TIMED_WAITING:
                        sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    default:
                }
            }

            for (MonitorInfo mi : threadInfo.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked ").append(mi);
                    if (mi.getIdentityHashCode() == lockIdentityHashCode) {
                        Ansi highlighted = Ansi.ansi().fg(Ansi.Color.RED);
                        highlighted.a(" <---- but blocks ").a(blockingThreadCount).a(" other threads!");
                        sb.append(highlighted.reset().toString());
                    }
                    sb.append('\n');
                }
            }
            ++i;
        }
        if (i < threadInfo.getStackTrace().length) {
            sb.append("\t...");
            sb.append('\n');
        }

        LockInfo[] locks = threadInfo.getLockedSynchronizers();
        if (locks.length > 0) {
            sb.append("\n\tNumber of locked synchronizers = ").append(locks.length);
            sb.append('\n');
            for (LockInfo li : locks) {
                sb.append("\t- ").append(li);
                if (li.getIdentityHashCode() == lockIdentityHashCode) {
                    sb.append(" <---- but blocks ").append(blockingThreadCount);
                    sb.append(" other threads!");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString().replace("\t", "    ");
    }

    public static String getFullStacktrace(BusyThreadInfo threadInfo, int lockIdentityHashCode, int blockingThreadCount) {
        if (threadInfo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\"" + threadInfo.getName() + "\"");
        if (threadInfo.getId() > 0) {
            sb.append(" Id=").append(threadInfo.getId());
        } else {
            sb.append(" [Internal]");
        }
        double cpuUsage = threadInfo.getCpu();
        if (cpuUsage >= 0 && cpuUsage <= 100) {
            sb.append(" cpuUsage=").append(cpuUsage).append("%");
        }
        if (threadInfo.getDeltaTime() >= 0 ) {
            sb.append(" deltaTime=").append(threadInfo.getDeltaTime()).append("ms");
        }
        if (threadInfo.getTime() >= 0 ) {
            sb.append(" time=").append(threadInfo.getTime()).append("ms");
        }

        if (threadInfo.getState() == null) {
            sb.append("\n\n");
            return sb.toString();
        }

        sb.append(" ").append(threadInfo.getState());

        if (threadInfo.getLockName() != null) {
            sb.append(" on ").append(threadInfo.getLockName());
        }
        if (threadInfo.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(threadInfo.getLockOwnerName()).append("\" Id=").append(threadInfo.getLockOwnerId());
        }
        if (threadInfo.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (threadInfo.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');
        int i = 0;
        for (; i < threadInfo.getStackTrace().length; i++) {
            StackTraceElement ste = threadInfo.getStackTrace()[i];
            sb.append("\tat ").append(ste.toString());
            sb.append('\n');
            if (i == 0 && threadInfo.getLockInfo() != null) {
                Thread.State ts = threadInfo.getState();
                switch (ts) {
                    case BLOCKED:
                        sb.append("\t-  blocked on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case WAITING:
                        sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case TIMED_WAITING:
                        sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    default:
                }
            }

            for (MonitorInfo mi : threadInfo.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked ").append(mi);
                    if (mi.getIdentityHashCode() == lockIdentityHashCode) {
                        Ansi highlighted = Ansi.ansi().fg(Ansi.Color.RED);
                        highlighted.a(" <---- but blocks ").a(blockingThreadCount).a(" other threads!");
                        sb.append(highlighted.reset().toString());
                    }
                    sb.append('\n');
                }
            }
        }
        if (i < threadInfo.getStackTrace().length) {
            sb.append("\t...");
            sb.append('\n');
        }

        LockInfo[] locks = threadInfo.getLockedSynchronizers();
        if (locks.length > 0) {
            sb.append("\n\tNumber of locked synchronizers = ").append(locks.length);
            sb.append('\n');
            for (LockInfo li : locks) {
                sb.append("\t- ").append(li);
                if (li.getIdentityHashCode() == lockIdentityHashCode) {
                    sb.append(" <---- but blocks ").append(blockingThreadCount);
                    sb.append(" other threads!");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');
        return sb.toString().replace("\t", "    ");
    }

    /**
     * </pre>
     * java.lang.Thread.getStackTrace(Thread.java:1559),
     * com.taobao.arthas.core.util.ThreadUtil.getThreadStack(ThreadUtil.java:349),
     * com.taobao.arthas.core.command.monitor200.StackAdviceListener.before(StackAdviceListener.java:33),
     * com.taobao.arthas.core.advisor.AdviceListenerAdapter.before(AdviceListenerAdapter.java:49),
     * com.taobao.arthas.core.advisor.SpyImpl.atEnter(SpyImpl.java:42),
     * java.arthas.SpyAPI.atEnter(SpyAPI.java:40),
     * demo.MathGame.print(MathGame.java), demo.MathGame.run(MathGame.java:25),
     * demo.MathGame.main(MathGame.java:16)
     * </pre>
     */
    private static int MAGIC_STACK_DEPTH = 0;

    private static int findTheSpyAPIDepth(StackTraceElement[] stackTraceElementArray) {
        if (MAGIC_STACK_DEPTH > 0) {
            return MAGIC_STACK_DEPTH;
        }
        if (MAGIC_STACK_DEPTH > stackTraceElementArray.length) {
            return 0;
        }
        for (int i = 0; i < stackTraceElementArray.length; ++i) {
            if (SpyAPI.class.getName().equals(stackTraceElementArray[i].getClassName())) {
                MAGIC_STACK_DEPTH = i + 1;
                break;
            }
        }
        return MAGIC_STACK_DEPTH;
    }

    /**
     * 获取方法执行堆栈信息
     *
     * @return 方法堆栈信息
     */
    public static StackModel getThreadStackModel(ClassLoader loader, Thread currentThread) {
        StackModel stackModel = new StackModel();
        stackModel.setThreadName(currentThread.getName());
        stackModel.setThreadId(Long.toString(currentThread.getId()));
        stackModel.setDaemon(currentThread.isDaemon());
        stackModel.setPriority(currentThread.getPriority());
        stackModel.setClassloader(getTCCL(currentThread));

        getEagleeyeTraceInfo(loader, currentThread, stackModel);


        //stack
        StackTraceElement[] stackTraceElementArray = currentThread.getStackTrace();
        int magicStackDepth = findTheSpyAPIDepth(stackTraceElementArray);
        StackTraceElement[] actualStackFrames = new StackTraceElement[stackTraceElementArray.length - magicStackDepth];
        System.arraycopy(stackTraceElementArray, magicStackDepth , actualStackFrames, 0, actualStackFrames.length);
        stackModel.setStackTrace(actualStackFrames);
        return stackModel;
    }

    public static ThreadNode getThreadNode(ClassLoader loader, Thread currentThread) {
        ThreadNode threadNode = new ThreadNode();
        threadNode.setThreadId(currentThread.getId());
        threadNode.setThreadName(currentThread.getName());
        threadNode.setDaemon(currentThread.isDaemon());
        threadNode.setPriority(currentThread.getPriority());
        threadNode.setClassloader(getTCCL(currentThread));

        //trace_id
        StackModel stackModel = new StackModel();
        getEagleeyeTraceInfo(loader, currentThread, stackModel);
        threadNode.setTraceId(stackModel.getTraceId());
        threadNode.setRpcId(stackModel.getRpcId());
        return threadNode;
    }

    public static String getThreadTitle(StackModel stackModel) {
        StringBuilder sb = new StringBuilder("thread_name=");
        sb.append(stackModel.getThreadName())
                .append(";id=").append(stackModel.getThreadId())
                .append(";is_daemon=").append(stackModel.isDaemon())
                .append(";priority=").append(stackModel.getPriority())
                .append(";TCCL=").append(stackModel.getClassloader());
        if (stackModel.getTraceId() != null) {
            sb.append(";trace_id=").append(stackModel.getTraceId());
        }
        if (stackModel.getRpcId() != null) {
            sb.append(";rpc_id=").append(stackModel.getRpcId());
        }
        return sb.toString();
    }

    private static String getTCCL(Thread currentThread) {
        ClassLoader contextClassLoader = currentThread.getContextClassLoader();
        if (null == contextClassLoader) {
            return "null";
        } else {
            return contextClassLoader.getClass().getName() +
                    "@" +
                    Integer.toHexString(contextClassLoader.hashCode());
        }
    }

    private static void getEagleeyeTraceInfo(ClassLoader loader, Thread currentThread, StackModel stackModel) {
        if(loader == null) {
            return;
        }
        Class<?> eagleEyeClass = null;
        if (!detectedEagleEye) {
            try {
                eagleEyeClass = loader.loadClass("com.taobao.eagleeye.EagleEye");
                foundEagleEye = true;
            } catch (Throwable e) {
                // ignore
            }
            detectedEagleEye = true;
        }

        if (!foundEagleEye) {
            return;
        }

        try {
            if (eagleEyeClass == null) {
                eagleEyeClass = loader.loadClass("com.taobao.eagleeye.EagleEye");
            }
            Method getTraceIdMethod = eagleEyeClass.getMethod("getTraceId");
            String traceId = (String) getTraceIdMethod.invoke(null);
            stackModel.setTraceId(traceId);
            Method getRpcIdMethod = eagleEyeClass.getMethod("getRpcId");
            String rpcId = (String) getRpcIdMethod.invoke(null);
            stackModel.setRpcId(rpcId);
        } catch (Throwable e) {
            // ignore
        }
    }
    /**

     *
     *
     * @param interval
     * @return
     */
    public static List<ThreadInfo> getBlockedThreads (long interval){
        ArrayList<ThreadInfo> blockedThreads = new ArrayList<>();
        Map<Long, LockInfo> threadAndBlockingLock = new HashMap<>();

        //
        List<ThreadVO> threads = ThreadUtil.getThreads();
        //mainid
        List<Long> mainThreadIds = new ArrayList<>();

        for(ThreadVO threadVO : threads) {
            if(threadVO.getGroup().equals("main")) {
                mainThreadIds.add(threadVO.getId());
            }
        }

        //
        ThreadInfo[]  infos = threadMXBean.dumpAllThreads (threadMXBean.isObjectMonitorUsageSupported(), threadMXBean.isSynchronizerUsageSupported());
        for (ThreadInfo threadInfo :infos) {
            LockInfo lockInfo = threadInfo.getLockInfo();
            long threadId = threadInfo.getThreadId();
            // && main
            if (lockInfo != null && mainThreadIds.contains(threadId)) {
                threadAndBlockingLock.put(threadId, lockInfo);
            }
        }
        //interval
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            //ignore
        }

        ThreadInfo[]  infosAfterInterval = threadMXBean.dumpAllThreads (threadMXBean.isObjectMonitorUsageSupported(),threadMXBean.isSynchronizerUsageSupported());
        for (ThreadInfo threadInfo : infosAfterInterval) {
            long threadId = threadInfo.getThreadId();
            LockInfo currentLockInfo = threadInfo.getLockInfo();
            if (threadAndBlockingLock.containsKey(threadId)  && currentLockInfo != null) {
                LockInfo previousLock = threadAndBlockingLock.get (threadId) ;
                if (previousLock.getIdentityHashCode() == currentLockInfo.getIdentityHashCode()) {
                    blockedThreads.add(threadInfo);
                }
            }
        }
        return blockedThreads;
    }
}

     * 检测系统中是否存在死锁情况
     * 死锁是指两个或两个以上的进程在执行过程中，由于竞争资源或者由于彼此通信而造成的一种阻塞的现象，若无外力作用，它们都将无法推进下去
     *
     * @return DeadlockInfo对象，包含死锁相关信息如果系统中没有发现死锁，返回一个空的DeadlockInfo对象
     */
    public static DeadlockInfo detectDeadlock(){
        // 初始化一个空的死锁信息对象，用于后续收集死锁相关信息
        DeadlockInfo deadlockInfo = new DeadlockInfo();

        // 获取系统中所有死锁线程的ID数组如果没有死锁线程，此方法将返回null
        long[] ids = threadMXBean.findDeadlockedThreads();

        // 如果没有死锁线程，直接返回空的死锁信息对象
        if(ids == null){
            return deadlockInfo;
        }

        // 获取死锁线程的详细信息，包括是否支持对象监视器和同步器的使用情况
        ThreadInfo[] threads = threadMXBean.getThreadInfo(ids,
                threadMXBean.isObjectMonitorUsageSupported(),threadMXBean.isSynchronizerUsageSupported());

        // 遍历每个死锁线程的信息
        for(ThreadInfo threadInfo : threads){
            // 获取当前线程持有的锁的信息
            LockInfo lockInfo = threadInfo.getLockInfo();

            // 如果当前线程持有的锁在死锁信息中尚未记录，则将当前线程信息添加到死锁信息中
            if(!deadlockInfo.getOwnerThreadPerLock().containsKey(lockInfo.getIdentityHashCode())){
                deadlockInfo.getThreads().add(threadInfo);
            }

            // 遍历当前线程持有的所有监视器锁，将每个锁及其持有线程的信息添加到死锁信息中
            for (MonitorInfo monitorInfo : threadInfo.getLockedMonitors()) {
                deadlockInfo.getOwnerThreadPerLock().putIfAbsent(monitorInfo.getIdentityHashCode(), threadInfo);
            }

            // 遍历当前线程持有的所有同步器锁，将每个锁及其持有线程的信息添加到死锁信息中
            for (LockInfo lockedSync : threadInfo.getLockedSynchronizers()) {
                deadlockInfo.getOwnerThreadPerLock().putIfAbsent(lockedSync.getIdentityHashCode(), threadInfo);
            }
        }

        // 返回收集到的死锁信息
        return deadlockInfo;
    }
