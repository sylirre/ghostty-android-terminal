package sh.easycli.proot.term;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import sh.easycli.proot.R;

/**
 * Keeps the app's process alive while shells are running.
 *
 * Sessions live in {@link SessionManager} (a process singleton); this
 * service owns no session state. Its only jobs are to (a) hold the process
 * at foreground-service OOM priority so the Low Memory Killer leaves the
 * shells alone, (b) survive a swipe from Recents (manifest
 * {@code stopWithTask="false"} plus the no-op {@link #onTaskRemoved}), and
 * (c) expose a persistent notification with an "Exit" action and an
 * optional CPU wake lock.
 *
 * The Activity drives the lifecycle: {@link #refresh} on every session
 * count change, {@link #stop} when the last tab closes. "Exit" fires
 * {@link #ACTION_EXIT}, which can run with no Activity alive, so the
 * service itself tears the sessions down and broadcasts {@link #ACTION_EXITED}
 * for any live Activity to finish on.
 */
public final class SessionService extends Service {

    /** Bring the service to (or keep it in) the foreground; refresh the notification. */
    private static final String ACTION_START = "sh.easycli.proot.service.START";
    /** Kill every shell and stop; user tapped "Exit". */
    private static final String ACTION_EXIT = "sh.easycli.proot.service.EXIT";
    /** Flip the CPU wake lock. */
    private static final String ACTION_TOGGLE_WAKELOCK = "sh.easycli.proot.service.TOGGLE_WAKELOCK";

    /** In-process broadcast telling a live Activity to finish after "Exit". */
    public static final String ACTION_EXITED = "sh.easycli.proot.service.EXITED";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "sessions";

    private PowerManager.WakeLock wakeLock;

    /** Ensures the service is running and its notification reflects the current state. */
    public static void refresh(Context context) {
        context.startForegroundService(intent(context, ACTION_START));
    }

    /** Stops the service; the persistent notification disappears with it. */
    public static void stop(Context context) {
        context.stopService(intent(context, null));
    }

    private static Intent intent(Context context, String action) {
        Intent i = new Intent(context, SessionService.class);
        if (action != null) i.setAction(action);
        return i;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_EXIT.equals(action)) {
            SessionManager.get().closeAll();
            // Tell a live Activity (if any) to finish and drop its task.
            sendBroadcast(new Intent(ACTION_EXITED).setPackage(getPackageName()));
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
            toggleWakeLock();
        }
        startForeground();
        // No point resurrecting an empty service: a restart cannot recover
        // the shells that died with the process.
        return START_NOT_STICKY;
    }

    /**
     * Swiping the task from Recents does not stop us (manifest
     * {@code stopWithTask="false"}); this override exists to make that intent
     * explicit and to keep the shells running. The Activity is gone, but the
     * singleton and the PTY reader threads ride along on the surviving process.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Intentionally do not stopSelf().
    }

    private void startForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is a 34+ foreground-service type; older releases
            // take the untyped overload (the manifest attribute is benign there).
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // IMPORTANCE_LOW: persistent but silent, no heads-up.
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Running sessions", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        int count = SessionManager.get().sessions().size();
        boolean held = wakeLock != null && wakeLock.isHeld();
        String text = count + (count == 1 ? " session running" : " sessions running")
                + (held ? " · wake lock on" : "");

        PendingIntent content = PendingIntent.getActivity(this, 0,
                getPackageManager().getLaunchIntentForPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent exit = PendingIntent.getService(this, 1,
                intent(this, ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent toggle = PendingIntent.getService(this, 2,
                intent(this, ACTION_TOGGLE_WAKELOCK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle("Terminal")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        held ? "Release wake lock" : "Acquire wake lock", toggle).build())
                .addAction(new Notification.Action.Builder(null, "Exit", exit).build())
                .build();
    }

    private void toggleWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            releaseWakeLock();
            return;
        }
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Terminal:sessions");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service only
    }
}
