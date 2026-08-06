package com.termux.zerocore.ccs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link CcsRestartPolicy} 的行为契约。
 *
 * <p>覆盖的是 sidecar 退出后宿主该怎么反应——这套规则的正确性直接决定「用户在设置页
 * 点重启之后 CC Switch 还能不能回来」，而它无法通过 APK 冒烟测试发现（重启失败的表现
 * 只是页面一直转圈）。
 */
public class CcsRestartPolicyTest {

    /** 手动推进的时钟，避免测试里真的 sleep 60 秒。 */
    private static final class FakeClock implements CcsRestartPolicy.Clock {
        private long now = 1_000_000L;

        @Override public long now() { return now; }

        void advance(long ms) { now += ms; }
    }

    // ── 退出码解读 ──────────────────────────────────────────────

    @Test public void treatsFiftyOneAsByDesignRestart() {
        // 51 来自 tauri-shim 的 RESTART_EXIT_CODE，是 sidecar 请求宿主重启的唯一可靠信号。
        assertTrue(CcsRestartPolicy.isByDesignRestart(51));
    }

    @Test public void treatsOtherExitCodesAsCrash() {
        assertFalse(CcsRestartPolicy.isByDesignRestart(0));
        assertFalse(CcsRestartPolicy.isByDesignRestart(1));
        assertFalse(CcsRestartPolicy.isByDesignRestart(-1));
        assertFalse(CcsRestartPolicy.isByDesignRestart(139));
    }

    @Test public void waitsLongerAfterCrashThanAfterByDesignRestart() {
        // 按设计退出只需等端口/文件锁释放；崩溃则可能是瞬时故障，等久一点避免密集重试。
        long byDesign = CcsRestartPolicy.restartDelayMs(51);
        long crash = CcsRestartPolicy.restartDelayMs(1);
        assertEquals(CcsRestartPolicy.RESTART_DELAY_MS, byDesign);
        assertEquals(CcsRestartPolicy.CRASH_RESTART_DELAY_MS, crash);
        assertTrue("崩溃后的等待必须比按设计退出更长", crash > byDesign);
    }

    // ── 自愈配额 ────────────────────────────────────────────────

    @Test public void allowsExactlyThreeAutoRestartsPerWindow() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);

        for (int i = 1; i <= CcsRestartPolicy.MAX_AUTO_RESTARTS; i++) {
            assertTrue("第 " + i + " 次重启应被允许", policy.claimAutoRestart());
        }
        assertFalse("超额后必须停手，否则起不来的二进制会被无限刷",
            policy.claimAutoRestart());
        assertEquals(CcsRestartPolicy.MAX_AUTO_RESTARTS, policy.usedAutoRestarts());
    }

    @Test public void resetsQuotaAfterWindowExpires() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);

        for (int i = 0; i < CcsRestartPolicy.MAX_AUTO_RESTARTS; i++) {
            assertTrue(policy.claimAutoRestart());
        }
        assertFalse(policy.claimAutoRestart());

        clock.advance(CcsRestartPolicy.RESTART_WINDOW_MS + 1);
        assertTrue("窗口过期后应重新放行，否则一次偶发故障会永久禁掉自愈",
            policy.claimAutoRestart());
        assertEquals(1, policy.usedAutoRestarts());
    }

    @Test public void keepsQuotaExhaustedWhileStillInsideWindow() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);

        for (int i = 0; i < CcsRestartPolicy.MAX_AUTO_RESTARTS; i++) {
            policy.claimAutoRestart();
        }
        clock.advance(CcsRestartPolicy.RESTART_WINDOW_MS - 1);
        assertFalse("窗口未过期就放行等于配额失效", policy.claimAutoRestart());
    }

    @Test public void explicitRestartDoesNotConsumeCrashQuota() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);

        for (int i = 0; i < CcsRestartPolicy.MAX_AUTO_RESTARTS; i++) {
            policy.claimAutoRestart();
        }
        assertFalse(policy.claimAutoRestart());

        // 用户在设置页手动点了重启：这不是故障，不该把崩溃自愈的额度用光。
        policy.resetAutoRestarts();
        assertTrue("手动重启后崩溃自愈必须重新可用", policy.claimAutoRestart());
        assertEquals(1, policy.usedAutoRestarts());
    }

    // ── 新鲜度（折叠重复重启请求）────────────────────────────────

    @Test public void treatsRecentStartAsFresh() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);
        long startedAt = clock.now();

        clock.advance(1_000L);
        assertTrue(policy.isFresh(startedAt, 5_000L));
    }

    @Test public void treatsOldStartAsStale() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);
        long startedAt = clock.now();

        clock.advance(5_001L);
        assertFalse(policy.isFresh(startedAt, 5_000L));
    }

    @Test public void treatsExactWindowBoundaryAsFresh() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);
        long startedAt = clock.now();

        clock.advance(5_000L);
        assertTrue("边界应含入，避免在临界点上反复重启", policy.isFresh(startedAt, 5_000L));
    }

    @Test public void neverStartedIsNotFresh() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);
        assertFalse("从未启动过不能被当成刚启动，否则首次重启会被跳过",
            policy.isFresh(0L, 5_000L));
    }

    @Test public void clockGoingBackwardsIsNotFresh() {
        FakeClock clock = new FakeClock();
        CcsRestartPolicy policy = new CcsRestartPolicy(clock);
        long startedAt = clock.now();

        // 系统时间被回拨（NTP 校正、用户改时间）。此时 elapsed 为负，
        // 无法判断新鲜度，宁可多重启一次也不要错误地复用一个已死的端口。
        clock.advance(-10_000L);
        assertFalse(policy.isFresh(startedAt, 5_000L));
    }
}
