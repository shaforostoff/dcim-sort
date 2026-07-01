package com.shaforostoff.dcimsort.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides how many parallel compression workers to run based on CPU cores and free RAM, and
 * supplies a {@link ThreadFactory} that pins workers to background priority. Android exposes no
 * way to pin threads to efficiency cores; {@code THREAD_PRIORITY_BACKGROUND} puts them in the
 * background cgroup, which the scheduler tends to keep on little cores while sparing the UI.
 */
public final class ThreadPlanner {
    private ThreadPlanner() {}

    /** Rough per-worker transient memory budget for decode+encode of one large photo. */
    private static final long PER_WORKER_BYTES = 96L * 1024 * 1024; // ~96 MB headroom per worker

    /**
     * @param ctx       context for ActivityManager
     * @param heavyEncode true for HEIC (hardware HEVC encoders often allow only 1–2 sessions)
     */
    public static int workerCount(Context ctx, boolean heavyEncode) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int desired = Math.min(4, cores);

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.lowMemory) {
                desired = 1;
            } else {
                long byMem = Math.max(1, mi.availMem / PER_WORKER_BYTES);
                desired = (int) Math.min(desired, byMem);
            }
        }
        if (heavyEncode) {
            // Clamp HEIC to at most 2 concurrent encoder sessions.
            desired = Math.min(desired, 2);
        }
        return Math.max(1, desired);
    }

    public static ThreadFactory backgroundFactory(final String namePrefix) {
        final AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, namePrefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
