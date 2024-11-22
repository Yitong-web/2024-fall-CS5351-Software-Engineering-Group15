package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.model.*;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.command.ExitStatus;
import com.taobao.arthas.core.util.ArrayUtils;
import com.taobao.arthas.core.util.CommandUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.ThreadUtil;
import com.taobao.middleware.cli.annotations.*;

import java.lang.Thread.State;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * @author hengyunabc 2015年12月7日 下午2:06:21
 */
@Name("thread")
@Summary("Display thread info, thread stack")
@Description(Constants.EXAMPLE +
        "  thread\n" +
        "  thread 51\n" +
        "  thread -n -1\n" +
        "  thread -n 5\n" +
        "  thread -b\n" +
        "  thread -i 2000\n" +
        "  thread --state BLOCKED\n" +
        Constants.WIKI + Constants.WIKI_HOME + "thread")
public class ThreadCommand extends AnnotatedCommand {
    private static Set<String> states = null;
    private static ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private long id = -1;
    private Integer topNBusy = null;
    private boolean findMostBlockingThread = false;
    private int sampleInterval = 200;
    private String state;

    private boolean lockedMonitors = false;
    private boolean lockedSynchronizers = false;
    private boolean all = false;

    private boolean deadlock = false;

    static {
        states = new HashSet<String>(State.values().length);
        for (State state : State.values()) {
            states.add(state.name());
        }
    }

    @Argument(index = 0, required = false, argName = "id")
    @Description("Show thread stack")
    public void setId(long id) {
        this.id = id;
    }

    @Option(longName = "all", flag = true)
    @Description("Display all thread results instead of the first page")
    public void setAll(boolean all) {
        this.all = all;
    }

    /**
     * 添加控制台死锁信息输出指令
     * @param deadlock
     */
    @Option(shortName = "d", longName = "deadlock", flag = true)
    @Description("Display all thread results instead of the first page")
    public void setDeadlock(boolean deadlock) {
        this.deadlock = deadlock;
    }

    @Option(shortName = "n", longName = "top-n-threads")
    @Description("The number of thread(s) to show, ordered by cpu utilization, -1 to show all.")
    public void setTopNBusy(Integer topNBusy) {
        this.topNBusy = topNBusy;
    }

    @Option(shortName = "b", longName = "include-blocking-thread", flag = true)
    @Description("Find the thread who is holding a lock that blocks the most number of threads.")
    public void setFindMostBlockingThread(boolean findMostBlockingThread) {
        this.findMostBlockingThread = findMostBlockingThread;
    }

    @Option(shortName = "i", longName = "sample-interval")
    @Description("Specify the sampling interval (in ms) when calculating cpu usage.")
    public void setSampleInterval(int sampleInterval) {
        this.sampleInterval = sampleInterval;
    }

    @Option(longName = "state")
    @Description("Display the thread filter by the state. NEW, RUNNABLE, TIMED_WAITING, WAITING, BLOCKED, TERMINATED is optional.")
    public void setState(String state) {
        this.state = state;
    }

    @Option(longName = "lockedMonitors", flag = true)
    @Description("Find the thread info with lockedMonitors flag, default value is false.")
    public void setLockedMonitors(boolean lockedMonitors) {
        this.lockedMonitors = lockedMonitors;
    }

    @Option(longName = "lockedSynchronizers", flag = true)
    @Description("Find the thread info with lockedSynchronizers flag, default value is false.")
    public void setLockedSynchronizers(boolean lockedSynchronizers) {
        this.lockedSynchronizers = lockedSynchronizers;
    }

    @Override
    public void process(CommandProcess process) {
        ExitStatus exitStatus;
        if (id > 0) {
            exitStatus = processThread(process);
        } else if (topNBusy != null) {
            exitStatus = processTopBusyThreads(process);
        } else if (findMostBlockingThread) {
            exitStatus = processBlockingThread(process);
        }else if (deadlock){
            exitStatus = processDeadlockThread(process);
        } else {
            exitStatus = processAllThreads(process);
        }
        CommandUtils.end(process, exitStatus);
    }

    private ExitStatus processAllThreads(CommandProcess process) {
        List<ThreadVO> threads = ThreadUtil.getThreads();

        // 统计各种线程状态
        Map<State, Integer> stateCountMap = new LinkedHashMap<State, Integer>();
        for (State s : State.values()) {
            stateCountMap.put(s, 0);
        }

        for (ThreadVO thread : threads) {
            State threadState = thread.getState();
            Integer count = stateCountMap.get(threadState);
            stateCountMap.put(threadState, count + 1);
        }

        boolean includeInternalThreads = true;
        Collection<ThreadVO> resultThreads = new ArrayList<ThreadVO>();
        if (!StringUtils.isEmpty(this.state)) {
            this.state = this.state.toUpperCase();
            if (states.contains(this.state)) {
                includeInternalThreads = false;
                for (ThreadVO thread : threads) {
                    if (thread.getState() != null && state.equals(thread.getState().name())) {
                        resultThreads.add(thread);
                    }
                }
            } else {
                return ExitStatus.failure(1, "Illegal argument, state should be one of " + states);
            }
        } else {
            resultThreads = threads;
        }

        //thread stats
        ThreadSampler threadSampler = new ThreadSampler();
        threadSampler.setIncludeInternalThreads(includeInternalThreads);
        threadSampler.sample(resultThreads);
        threadSampler.pause(sampleInterval);
        List<ThreadVO> threadStats = threadSampler.sample(resultThreads);

        process.appendResult(new ThreadModel(threadStats, stateCountMap, all));
        return ExitStatus.success();
    }

    private ExitStatus processBlockingThread(CommandProcess process) {
        BlockingLockInfo blockingLockInfo = ThreadUtil.findMostBlockingLock();
        if (blockingLockInfo.getThreadInfo() == null) {
            return ExitStatus.failure(1, "No most blocking thread found!");
        }
        process.appendResult(new ThreadModel(blockingLockInfo));
        return ExitStatus.success();
    }

    private ExitStatus processDeadlockThread(CommandProcess process) {
        DeadlockInfo deadlockInfo = ThreadUtil.detectDeadlock();
        if (deadlockInfo.getThreads().size() == 0) {
            return ExitStatus.failure(1, "No deadlocks found!");
        }
        process.appendResult(new ThreadModel(deadlockInfo));
        return ExitStatus.success();
    }

    public static String render(DeadlockInfo deadlock){
        Map<Integer, ThreadInfo> ownerThreadPerLock = deadlock.getOwnerThreadPerLock();
        StringBuilder sb = new StringBuilder();
        for(ThreadInfo thread : deadlock.getThreads()){
            LockInfo waitingToLockInfo;
            ThreadInfo currentThread = thread;
            sb.append("Found one Java-level deadlock:\n");
            sb.append("=============================\n");
            do{
                sb.append(currentThread.getThreadName() + "(" + currentThread.getThreadId() + "):\n");
                waitingToLockInfo = currentThread.getLockInfo();
                if(waitingToLockInfo != null){
                    sb.append("  waiting to lock info @" + waitingToLockInfo + ",\n");
                    sb.append("  which is held by ");
                    currentThread = ownerThreadPerLock.get(waitingToLockInfo.getIdentityHashCode());
                    sb.append(currentThread.getThreadName() + "\n");
                }
            }while (!currentThread.equals(thread));
            sb.append("\n");
        }
        int numberOfDeadlocks = deadlock.getThreads().size();
        switch (numberOfDeadlocks) {
            case 0:
                sb.append("No deadlocks found.\n");
                break;
            case 1:
                sb.append("Found a total of 1 deadlock.\n");
                break;
            default:
                sb.append("Found a total of " + numberOfDeadlocks + " deadlocks.\n");
                break;
        }
        return sb.toString();
    }

    private ExitStatus processTopBusyThreads(CommandProcess process) {
        ThreadSampler threadSampler = new ThreadSampler();
        threadSampler.sample(ThreadUtil.getThreads());
        threadSampler.pause(sampleInterval);
        List<ThreadVO> threadStats = threadSampler.sample(ThreadUtil.getThreads());

        int limit = Math.min(threadStats.size(), topNBusy);

        List<ThreadVO> topNThreads = null;
        if (limit > 0) {
            topNThreads = threadStats.subList(0, limit);
        } else { // -1 for all threads
            topNThreads = threadStats;
        }

        List<Long> tids = new ArrayList<Long>(topNThreads.size());
        for (ThreadVO thread : topNThreads) {
            if (thread.getId() > 0) {
                tids.add(thread.getId());
            }
        }

        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(ArrayUtils.toPrimitive(tids.toArray(new Long[0])), lockedMonitors, lockedSynchronizers);
        if (tids.size()> 0 && threadInfos == null) {
            return ExitStatus.failure(1, "get top busy threads failed");
        }

        //threadInfo with cpuUsage
        List<BusyThreadInfo> busyThreadInfos = new ArrayList<BusyThreadInfo>(topNThreads.size());
        for (ThreadVO thread : topNThreads) {
            ThreadInfo threadInfo = findThreadInfoById(threadInfos, thread.getId());
            if (threadInfo != null) {
                BusyThreadInfo busyThread = new BusyThreadInfo(thread, threadInfo);
                busyThreadInfos.add(busyThread);
            }
        }
        process.appendResult(new ThreadModel(busyThreadInfos));
        return ExitStatus.success();
    }

    private ThreadInfo findThreadInfoById(ThreadInfo[] threadInfos, long id) {
        for (int i = 0; i < threadInfos.length; i++) {
            ThreadInfo threadInfo = threadInfos[i];
            if (threadInfo != null && threadInfo.getThreadId() == id) {
                return threadInfo;
            }
        }
        return null;
    }

    private ExitStatus processThread(CommandProcess process) {
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(new long[]{id}, lockedMonitors, lockedSynchronizers);
        if (threadInfos == null || threadInfos.length < 1 || threadInfos[0] == null) {
            return ExitStatus.failure(1, "thread do not exist! id: " + id);
        }

        process.appendResult(new ThreadModel(threadInfos[0]));
        return ExitStatus.success();
    }
}
