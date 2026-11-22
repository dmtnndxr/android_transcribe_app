package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * Makes the voice keyboard a microphone foreground service for as long as a
 * dictation runs. Without it Android feeds the recording pure silence as soon
 * as the keyboard is hidden, and may kill the process while it transcribes.
 * The notification is also how the user sees and stops a background recording.
 */
final class RecordingNotification {

    static final String ACTION_STOP = "dev.notune.transcribe.action.STOP_RECORDING";
    static final String ACTION_CANCEL = "dev.notune.transcribe.action.CANCEL_RECORDING";
    /** Android 14+ lets the user swipe the notification away. */
    static final String ACTION_DISMISSED = "dev.notune.transcribe.action.NOTIFICATION_DISMISSED";
    static final String CHANNEL = "recording";
    private static final int ID = 2;

    private RecordingNotification() {}

    static void createChannel(Context ctx) {
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, ctx.getString(R.string.notif_recording_channel),
                        NotificationManager.IMPORTANCE_LOW));
    }

    /** Whether the user would see a recording that runs in the background. */
    static boolean canShow(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** Must be called while the keyboard is visible: that is what grants the microphone. */
    static boolean start(Service service, long startedAt) {
        try {
            createChannel(service);
            Notification notification = recording(service, startedAt, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.startForeground(ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                service.startForeground(ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            DebugLog.error(service, "foreground start failed", e);
            return false;
        }
    }

    // Updates are notify(), never a second startForeground: from the background
    // that would lose the microphone grant or be refused.

    static void showRecording(Service service, long startedAt, boolean reminder) {
        update(service, recording(service, startedAt, reminder));
    }

    /** @param progress how far it got, or null */
    static void showTranscribing(Service service, String progress) {
        Notification.Builder b = base(service)
                .setContentTitle(service.getString(R.string.notif_transcribing))
                .addAction(cancelAction(service));
        if (progress != null) b.setContentText(progress);
        update(service, b.build());
    }

    static void stop(Service service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    private static void update(Service service, Notification notification) {
        try {
            service.getSystemService(NotificationManager.class).notify(ID, notification);
        } catch (RuntimeException e) {
            DebugLog.error(service, "notification update failed", e);
        }
    }

    private static Notification recording(Context ctx, long startedAt, boolean reminder) {
        Notification.Builder b = base(ctx)
                .setContentTitle(ctx.getString(reminder
                        ? R.string.notif_still_recording : R.string.notif_recording))
                .setContentText(ctx.getString(R.string.notif_recording_text))
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(startedAt)
                .setDeleteIntent(intent(ctx, ACTION_DISMISSED));
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_stop),
                ctx.getString(R.string.ime_stop_label), intent(ctx, ACTION_STOP)).build());
        b.addAction(cancelAction(ctx));
        return b.build();
    }

    private static Notification.Action cancelAction(Context ctx) {
        Notification.Action.Builder b = new Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_close),
                ctx.getString(R.string.ime_cancel), intent(ctx, ACTION_CANCEL));
        // Cancel throws audio away, so not from a locked phone in a pocket.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAuthenticationRequired(true);
        return b.build();
    }

    private static Notification.Builder base(Context ctx) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b;
    }

    private static PendingIntent intent(Context ctx, String action) {
        return PendingIntent.getBroadcast(ctx, 0,
                new Intent(action).setPackage(ctx.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
