package com.termux.zerocore.ccs;

/**
 * sidecar 退出后的重启决策：怎么解读退出码、等多久、还允许重启几次。
 *
 * <p>为什么独立成类：这些规则来自 cc-switch 的协议约定与运维经验，与「怎么拉起进程」
 * 无关。放在 {@link CcsSidecar} 里会和 {@code ProcessBuilder}、握手读取、Android
 * {@code Context} 纠缠在一起，既读不清也测不到。抽出来之后它是纯逻辑 + 可注入时钟，
 * 能被单元测试完整覆盖。
 *
 * <p>本类不是线程安全的。{@link CcsSidecar} 在自己的锁内调用。
 */
final class CcsRestartPolicy {

    /**
     * sidecar 主动请求宿主重启自己时使用的退出码。
     *
     * <p>来自 {@code tauri-shim/src/lib.rs} 的 {@code RESTART_EXIT_CODE}：桌面 Tauri 的
     * {@code app.restart()} 由框架负责重新拉起进程，Android 上的 shim 无法自己复活，
     * 于是约定「以 51 退出，由宿主重启」。前端设置页改配置目录后调用的
     * {@code restart_app} 走的正是这条路径。
     *
     * <p>实测：sidecar 同时会发一条 {@code host-action{kind:"restart"}} SSE 事件，但那条
     * 事件永远送不到宿主——{@code AppHandle::restart()} 在同一同步调用栈上紧接
     * {@code std::process::exit(51)}，tokio 没有 flush 的机会。所以退出码是唯一可靠信号。
     */
    static final int RESTART_EXIT_CODE = 51;

    /** 自动重启配额上限（每 {@link #RESTART_WINDOW_MS} 窗口）。防止起不来时无限刷进程。 */
    static final int MAX_AUTO_RESTARTS = 3;
    static final long RESTART_WINDOW_MS = 60_000L;
    /** 收到「按设计退出」后的重启间隔：只需让端口与文件锁释放干净。 */
    static final long RESTART_DELAY_MS = 300L;
    /** 意外崩溃后的重启间隔：留出更多余量，避免瞬时故障导致密集重试。 */
    static final long CRASH_RESTART_DELAY_MS = 1_500L;

    /** 可注入时钟，便于测试推进时间而不真的 sleep。 */
    interface Clock {
        long now();
    }

    private final Clock clock;
    private int autoRestarts;
    private long windowStart;

    CcsRestartPolicy(Clock clock) {
        this.clock = clock;
        this.windowStart = clock.now();
    }

    /** 该退出码是否为 sidecar 按设计请求的重启（而非崩溃）。 */
    static boolean isByDesignRestart(int exitCode) {
        return exitCode == RESTART_EXIT_CODE;
    }

    /** 重启前应等待的毫秒数。按设计退出等得短，崩溃等得长。 */
    static long restartDelayMs(int exitCode) {
        return isByDesignRestart(exitCode) ? RESTART_DELAY_MS : CRASH_RESTART_DELAY_MS;
    }

    /**
     * 领取一次自动重启配额。
     *
     * <p>按设计退出与崩溃共用同一份配额：两者都可能因为二进制本身起不来而无限循环，
     * 区分它们只会让「反复退出」这个真正需要止损的情形漏掉一半。
     *
     * @return true 表示允许重启；false 表示窗口内已超额，应停手并告知用户。
     */
    boolean claimAutoRestart() {
        long now = clock.now();
        if (now - windowStart > RESTART_WINDOW_MS) {
            windowStart = now;
            autoRestarts = 0;
        }
        if (autoRestarts >= MAX_AUTO_RESTARTS) return false;
        autoRestarts++;
        return true;
    }

    /**
     * 重置配额。用户/前端显式要求的重启不该消耗崩溃自愈的额度，
     * 否则「手动重启三次」之后真正的崩溃就救不回来了。
     */
    void resetAutoRestarts() {
        autoRestarts = 0;
        windowStart = clock.now();
    }

    /**
     * 判断某次启动是否「刚发生过」，用于把重复的重启请求折叠成复用。
     *
     * @param startedAt    上次成功启动的时刻，0 表示从未启动
     * @param freshWindowMs 认定为新鲜的时间窗
     */
    boolean isFresh(long startedAt, long freshWindowMs) {
        if (startedAt <= 0L) return false;
        long elapsed = clock.now() - startedAt;
        // elapsed 为负说明时钟被回拨，此时无法判断新鲜度，按不新鲜处理（宁可多重启一次）。
        return elapsed >= 0L && elapsed <= freshWindowMs;
    }

    /** 当前窗口内已用掉的自动重启次数，仅供日志/测试观察。 */
    int usedAutoRestarts() {
        return autoRestarts;
    }
}
