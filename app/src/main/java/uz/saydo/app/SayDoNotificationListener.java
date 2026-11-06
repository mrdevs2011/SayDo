package uz.saydo.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the last notifications in memory (never on disk, never logged) so the AI can answer
 * "what came to me" through the read_notifications MCP tool. It stores nothing unless the owner
 * switched on the setting in the MCP screen; Android's Notification access must also be granted.
 */
public class SayDoNotificationListener extends NotificationListenerService {
    static final NotifBuffer BUFFER = new NotifBuffer(NotifBuffer.CAPACITY);
    static volatile SayDoNotificationListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        BUFFER.clear();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        BUFFER.clear();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!new McpConfig(this).aiNotifications()) {
            BUFFER.clear();
            return;
        }
        NotifBuffer.Entry e = toEntry(this, sbn);
        if (e != null) BUFFER.add(e);
    }

    /** Null when the notification is not interesting (own, ongoing, foreground service, group summary). */
    static NotifBuffer.Entry toEntry(Context c, StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return null;
        Notification n = sbn.getNotification();
        boolean own = c.getPackageName().equals(sbn.getPackageName());
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean fgs = (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (!NotifBuffer.accept(own, ongoing, fgs, summary)) return null;
        Bundle x = n.extras;
        String title = "", text = "";
        if (x != null) {
            title = cs(x.getCharSequence(Notification.EXTRA_TITLE));
            text = cs(x.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) text = cs(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (title.isEmpty() && text.isEmpty()) return null;
        return new NotifBuffer.Entry(sbn.getKey(), sbn.getPostTime(), sbn.getPackageName(),
                label(c, sbn.getPackageName()), title, text);
    }

    private static String cs(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return cs(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Buffered plus active now. Only call after the consent checks. */
    static List<NotifBuffer.Entry> current(Context c) {
        List<NotifBuffer.Entry> active = new ArrayList<>();
        SayDoNotificationListener l = instance;
        if (l != null) {
            try {
                StatusBarNotification[] arr = l.getActiveNotifications();
                if (arr != null) {
                    for (StatusBarNotification sbn : arr) {
                        NotifBuffer.Entry e = toEntry(c, sbn);
                        if (e != null) active.add(e);
                    }
                }
            } catch (Throwable ignored) {
                // service not ready: the buffer alone is used
            }
        }
        return NotifBuffer.merge(BUFFER.snapshot(), active);
    }

    /** Android's Notification access for SayDo (Settings > Notifications > Device & app notifications). */
    static boolean accessGranted(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        for (String part : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && cn.getPackageName().equals(c.getPackageName())) return true;
        }
        return false;
    }
}
