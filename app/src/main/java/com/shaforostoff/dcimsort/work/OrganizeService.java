package com.shaforostoff.dcimsort.work;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.geo.CoordCache;
import com.shaforostoff.dcimsort.geo.GeoCache;
import com.shaforostoff.dcimsort.geo.GeoExtractor;
import com.shaforostoff.dcimsort.geo.PlaceResolver;
import com.shaforostoff.dcimsort.util.Sdk;
import com.shaforostoff.dcimsort.util.ThreadPlanner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service that runs the Organize job: resolves each photo's target folder (newest
 * first), then moves or recompresses it across a small worker pool. Shows progress + a Stop action
 * in its notification and survives Activity recreation. Stopping is graceful (no orphaned files).
 */
public class OrganizeService extends Service {
    public static final String ACTION_START = "com.shaforostoff.dcimsort.START";
    public static final String ACTION_STOP = "com.shaforostoff.dcimsort.STOP";

    private static final String CHANNEL_ID = "organize";
    private static final int NOTI_ID = 1001;

    /** Listener for the foreground UI. */
    public interface Listener {
        void onProgress(int done, int total, String folder);
        void onDone(int moved, int skipped, int failed, boolean stopped);
    }

    // Last-known state so a freshly-bound Activity renders immediately.
    public static volatile boolean RUNNING = false;
    public static volatile int P_DONE, P_TOTAL, P_MOVED, P_SKIPPED, P_FAILED;
    public static volatile String P_FOLDER = "";
    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String currentFolder = "";
    private long lastNotifyAt = 0;
    private Thread control;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop.set(true);
            return START_NOT_STICKY;
        }
        if (RUNNING) {
            return START_NOT_STICKY;
        }
        OrganizeRequest req = OrganizeRequest.take();
        if (req == null || req.images == null || req.images.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        RUNNING = true;
        stop.set(false);
        P_DONE = 0;
        P_TOTAL = req.images.size();
        P_MOVED = P_SKIPPED = P_FAILED = 0;
        P_FOLDER = "";

        createChannel();
        startInForeground(buildNotification(0, P_TOTAL, ""));

        control = new Thread(() -> run(req), "organize-control");
        control.start();
        return START_NOT_STICKY;
    }

    private void run(OrganizeRequest req) {
        ContentResolver resolver = getContentResolver();

        MediaRepository repo = new MediaRepository(this);
        GeoCache cache = new GeoCache(this);
        CoordCache coordCache = new CoordCache(this);
        GeoExtractor geo = new GeoExtractor(repo);
        PlaceResolver places = new PlaceResolver(this, cache);
        TargetResolver targets = new TargetResolver(geo, places,
                req.groupMode != null ? req.groupMode : com.shaforostoff.dcimsort.data.GroupMode.PLACE_MONTH,
                coordCache);
        OrganizeJournal journal = new OrganizeJournal(this);
        journal.reconcile(resolver);
        Mover mover = new Mover(this, journal);
        Recompressor rc = new Recompressor(this, repo);

        boolean heavy = req.mode == com.shaforostoff.dcimsort.data.CompressMode.HEIC;
        int workers = ThreadPlanner.workerCount(this, heavy);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(workers * 4),
                ThreadPlanner.backgroundFactory("organize"),
                new ThreadPoolExecutor.CallerRunsPolicy());

        final int total = req.images.size();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger moved = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger sinceFlush = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (final MediaImage img : req.images) {
            if (stop.get()) break;
            futures.add(pool.submit(() -> {
                if (stop.get()) return;
                try {
                    String folder = targets.folderFor(img);
                    currentFolder = folder;
                    String srcRel = req.sourceRelativePath != null
                            ? req.sourceRelativePath : img.relativePath;
                    boolean recompress = req.mode.recompresses() && !(req.skipFavorites && img.favorite);
                    // Copy (keep original) when the user asked, or when the source isn't movable
                    // in place (e.g. a Google Photos cloud pick with no MediaStore row).
                    boolean copy = req.keepOriginal || !img.isMovable();
                    if (copy) {
                        File temp = recompress
                                ? rc.compressToTemp(img.readUri(), req.mode, req.quality) : null;
                        boolean ok;
                        try {
                            // If recompression was requested but failed, fall back to an exact copy.
                            ok = mover.importCopy(img, temp, req.mode, srcRel, folder, req.volumeName);
                        } finally {
                            if (temp != null) temp.delete();
                        }
                        if (ok) moved.incrementAndGet();
                        else failed.incrementAndGet();
                    } else if (!recompress) {
                        Mover.Outcome o = mover.move(img, srcRel, folder);
                        if (o == Mover.Outcome.MOVED) moved.incrementAndGet();
                        else if (o == Mover.Outcome.SKIPPED) skipped.incrementAndGet();
                        else failed.incrementAndGet();
                    } else {
                        File temp = rc.compressToTemp(img.readUri(), req.mode, req.quality);
                        boolean ok = false;
                        if (temp != null) {
                            try {
                                ok = mover.publishRecompressed(
                                        img, temp, req.mode, srcRel, folder, req.volumeName);
                            } finally {
                                temp.delete();
                            }
                        }
                        if (ok) moved.incrementAndGet();
                        else failed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    android.util.Log.e("DCIMSort", "organize error: " + img.contentUri(), t);
                } finally {
                    int d = done.incrementAndGet();
                    publish(d, total, currentFolder, moved.get(), skipped.get(), failed.get());
                    if (sinceFlush.incrementAndGet() >= 25) {
                        sinceFlush.set(0);
                        cache.flush();
                        coordCache.flush();
                    }
                }
            }));
        }

        pool.shutdown();
        try {
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                if (stop.get()) {
                    pool.shutdownNow();
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                    break;
                }
            }
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        cache.flush();
        coordCache.flush();

        boolean stopped = stop.get();
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onDone(moved.get(), skipped.get(), failed.get(), stopped);
        });
        finishService();
    }

    private void publish(int done, int total, String folder,
                         int moved, int skipped, int failed) {
        P_DONE = done;
        P_TOTAL = total;
        P_MOVED = moved;
        P_SKIPPED = skipped;
        P_FAILED = failed;
        P_FOLDER = folder;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onProgress(done, total, folder);
        });
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt > 500 || done == total) {
            lastNotifyAt = now;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(done, total, folder));
        }
    }

    private void finishService() {
        RUNNING = false;
        if (Sdk.atLeastQ()) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // ---- Notification -------------------------------------------------------

    private void createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.organize_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startInForeground(Notification n) {
        if (Sdk.atLeastU()) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (Sdk.atLeastQ()) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(int done, int total, String folder) {
        Intent stopIntent = new Intent(this, OrganizeService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, flags);

        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.organize_notification_title))
                .setContentText(getString(R.string.organizing_progress, done, total,
                        folder == null ? "" : folder))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setProgress(Math.max(1, total), done, total <= 0)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.stop), stopPi);
        return b.build();
    }

    // ---- Static helpers for the Activity ------------------------------------

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, OrganizeService.class).setAction(ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, OrganizeService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }
}
