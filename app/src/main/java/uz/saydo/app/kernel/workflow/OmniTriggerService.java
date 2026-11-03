package uz.saydo.app.kernel.workflow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;

/**
 * Contextual & Proactive Trigger Subsystem.
 * Monitors device sensors, power states, and geofence locations to trigger automations proactively.
 */
public final class OmniTriggerService {

    public interface ContextTriggerListener {
        void onTriggerActivated(String triggerType, String metadata);
    }

    private final Context context;
    private ContextTriggerListener listener;
    private BroadcastReceiver powerReceiver;

    public OmniTriggerService(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
    }

    public void setListener(ContextTriggerListener listener) {
        this.listener = listener;
    }

    public void registerPowerTriggers() {
        if (context == null) return;
        try {
            powerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || listener == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                        listener.onTriggerActivated("POWER_CONNECTED", "Charging");
                    } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                        listener.onTriggerActivated("POWER_DISCONNECTED", "Battery");
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(powerReceiver, filter);
        } catch (Throwable t) {
            android.util.Log.w("OmniTriggerService", "Power receiver registration failed", t);
        }
    }

    /**
     * Checks if current location is within target geofence radius (in meters).
     */
    public static boolean isInsideGeofence(double currentLat, double currentLon,
                                          double targetLat, double targetLon,
                                          float radiusMeters) {
        float[] results = new float[1];
        Location.distanceBetween(currentLat, currentLon, targetLat, targetLon, results);
        return results[0] <= radiusMeters;
    }

    public void unregister() {
        if (context != null && powerReceiver != null) {
            try {
                context.unregisterReceiver(powerReceiver);
            } catch (Throwable ignored) {
            }
            powerReceiver = null;
        }
    }
}
