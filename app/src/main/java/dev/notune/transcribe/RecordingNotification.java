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
 */
final class RecordingNotification {

    static final String ACTION_STOP = "dev.notune.transcribe.action.STOP_RECORDING";
    private static final String CHANNEL = "recording";
    private static final int ID = 2;

    private RecordingNotification() {}

    /** Must be called while the keyboard is visible: that is what grants the microphone. */
    static boolean start(Service service) {
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    service.getString(R.string.notif_recording_channel),
                    NotificationManager.IMPORTANCE_LOW);
            service.getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = build(service, true);
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

    /**
     * Switches the text to "Transcribing". Not a second startForeground: from
     * the background that would lose the microphone grant or be refused.
     */
    static void showTranscribing(Service service) {
        try {
            service.getSystemService(NotificationManager.class).notify(ID, build(service, false));
        } catch (RuntimeException e) {
            DebugLog.error(service, "notification update failed", e);
        }
    }

    static void stop(Service service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    private static Notification build(Context ctx, boolean recording) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(ctx.getString(recording
                        ? R.string.notif_recording : R.string.notif_transcribing))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (recording) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis());
            PendingIntent stop = PendingIntent.getBroadcast(ctx, 0,
                    new Intent(ACTION_STOP).setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_stop),
                    ctx.getString(R.string.ime_stop_label), stop).build());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
