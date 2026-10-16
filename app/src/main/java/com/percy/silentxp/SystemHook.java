package com.percy.silentxp;

import android.app.Notification;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.VibrationAttributes;
import android.provider.Telephony;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Only installed in system_server. Never modifies stream volumes or ringer/DND settings. */
public final class SystemHook implements IXposedHookLoadPackage {
    private static final String TAG = "ECSilent";
    private static final String AUDIO_TAG = "ecs.authorized.notification";
    private static volatile Policy policy = Policy.off();
    private static volatile Context context;
    private static volatile Object monitor, vibrator;
    private static volatile Handler handler;
    private static volatile boolean initialized, audioHook, notificationHook, vibrationHook, callHook;
    private static volatile boolean configLoaded;
    private static volatile String error = "";
    private static volatile Set<Integer> allowedUids = Set.of();
    private static final Map<Integer, Boolean> controlledPlayers = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> callStates = new ConcurrentHashMap<>();
    private static final Map<Integer, String> ringingNumbers = new ConcurrentHashMap<>();
    private static final Map<Object, Boolean> notificationDecisions = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> notificationVibration = ThreadLocal.withInitial(() -> 0);
    private static final AtomicLong mutedAudio = new AtomicLong(), blockedVibration = new AtomicLong();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"android".equals(p.packageName) || !"android".equals(p.processName)) return;
        ClassLoader loader = p.classLoader;
        try { installAudio(loader); audioHook = true; } catch (Throwable t) { fail("audio", t); }
        try { installNotifications(loader); notificationHook = true; } catch (Throwable t) { fail("notifications", t); }
        try { installVibration(loader); vibrationHook = true; } catch (Throwable t) { fail("vibration", t); }
        try { installCalls(loader); callHook = true; } catch (Throwable t) { fail("calls", t); }
        try {
            Class<?> ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader);
            hookRequired(ams, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (initialized) return;
                    try {
                        Context c = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        new Handler(Looper.getMainLooper()).post(() -> initialize(c));
                    } catch (Throwable t) { fail("initialize", t); }
                }
            });
        } catch (Throwable t) { fail("startup", t); }
        log("Hooks installed: audio=" + audioHook + " notification=" + notificationHook
                + " vibration=" + vibrationHook + " calls=" + callHook);
    }

    private static void hookRequired(Class<?> c, String name, XC_MethodHook hook) {
        if (XposedBridge.hookAllMethods(c, name, hook).isEmpty()) throw new IllegalStateException(c.getName() + "." + name + " missing");
    }
    private static void initialize(Context c) {
        if (initialized) return;
        try {
            context = c;
            handler = new Handler(Looper.getMainLooper());
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    reload();
                    Bundle status = new Bundle();
                    status.putBoolean("ready", ready());
                    status.putBoolean("enabled", policy.enabled);
                    status.putString("error", error);
                    status.putLong("audio", mutedAudio.get());
                    status.putLong("vibration", blockedVibration.get());
                    status.putInt("players", controlledPlayers.size());
                    setResultExtras(status);
                }
            }, new IntentFilter(Config.ACTION), Config.PERMISSION, handler, Context.RECEIVER_EXPORTED);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { reload(); }
            }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            AudioManager am = c.getSystemService(AudioManager.class);
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { reapply(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { reapply(); }
            }, handler);
            initialized = true;
            reload();
            handler.post(new Runnable() {
                @Override public void run() {
                    if (policy.enabled || !controlledPlayers.isEmpty()) reapply();
                    handler.postDelayed(this, 400);
                }
            });
            log("System bridge ready");
        } catch (Throwable t) { fail("bridge", t); }
    }

    private static boolean ready() { return initialized && configLoaded && audioHook && notificationHook && vibrationHook && callHook && error.isEmpty(); }

    private static synchronized void reload() {
        if (context == null) return;
        long token = Binder.clearCallingIdentity();
        try {
            Policy next = Config.policy(context.getContentResolver().call(Config.URI, "read", null, null));
            Set<Integer> uids = new HashSet<>();
            for (String pkg : next.packages) {
                try { uids.add(context.getPackageManager().getPackageUid(pkg, 0)); }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            }
            boolean changed = policy.enabled != next.enabled || !policy.numbers.equals(next.numbers)
                    || !policy.packages.equals(next.packages) || !allowedUids.equals(uids);
            allowedUids = Set.copyOf(uids);
            if (changed) notificationDecisions.clear();
            policy = audioHook && notificationHook && vibrationHook && callHook && error.isEmpty() ? next : Policy.off();
            configLoaded = true;
            reapply();
            if (changed) cancelBlockedVibrations();
        } catch (Throwable t) {
            // An unavailable configuration must never strand the phone in silent mode.
            policy = Policy.off();
            configLoaded = false;
            reapply();
            log("Config unavailable; blocking disabled (" + t.getClass().getSimpleName() + ")");
        } finally { Binder.restoreCallingIdentity(token); }
    }

    private static void installAudio(ClassLoader loader) {
        Class<?> c = XposedHelpers.findClass("com.android.server.audio.PlaybackActivityMonitor", loader);
        XC_MethodHook capture = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                monitor = p.thisObject;
                if (p.hasThrowable()) return;
                try {
                    int id = p.method.getName().equals("trackPlayer") ? (Integer) p.getResult() : (Integer) p.args[0];
                    Object apc;
                    Object lock = XposedHelpers.getObjectField(monitor, "mPlayerLock");
                    synchronized (lock) {
                        Map<?, ?> players = (Map<?, ?>) XposedHelpers.getObjectField(monitor, "mPlayers");
                        apc = players.get(id);
                    }
                    if (apc != null) applyPlayer(apc);
                } catch (Throwable t) { fail("player", t); }
            }
        };
        hookRequired(c, "trackPlayer", capture);
        hookRequired(c, "playerEvent", capture);
        hookRequired(c, "playerAttributes", capture);
        hookRequired(c, "releasePlayer", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { controlledPlayers.remove((Integer) p.args[0]); }
        });
    }

    private static void reapply() {
        Object m = monitor;
        if (m == null) return;
        try {
            List<Object> snapshot;
            synchronized (XposedHelpers.getObjectField(m, "mPlayerLock")) {
                snapshot = new ArrayList<>(((Map<?, ?>) XposedHelpers.getObjectField(m, "mPlayers")).values());
            }
            for (Object apc : snapshot) applyPlayer(apc);
        } catch (Throwable t) { fail("reapply", t); }
    }

    private static synchronized void applyPlayer(Object apc) {
        int id = (Integer) XposedHelpers.callMethod(apc, "getPlayerInterfaceId");
        int uid = (Integer) XposedHelpers.callMethod(apc, "getClientUid");
        AudioAttributes attrs = (AudioAttributes) XposedHelpers.callMethod(apc, "getAudioAttributes");
        boolean allowed = policy.audioAllowed(attrs.getUsage(), allowedUids.contains(uid),
                headphonesOnly(attrs), connectedCall(), allowedRinging(), hasAuthorizedTag(attrs));
        Boolean previous = controlledPlayers.get(id);
        if (allowed && previous == null) return;
        if (previous != null && previous == !allowed) return;
        Object proxy = XposedHelpers.callMethod(apc, "getPlayerProxy");
        if (proxy == null) return;
        try {
            // A separate multiplier preserves the app's own volume and all system volume settings.
            XposedHelpers.callMethod(proxy, "setVolume", allowed ? 1f : 0f);
            if (allowed) controlledPlayers.remove(id);
            else { controlledPlayers.put(id, true); mutedAudio.incrementAndGet(); }
        } catch (Throwable t) {
            // A player may be released concurrently. Retry remaining active players on next update.
            log("Player " + id + " control failed: " + t.getClass().getSimpleName());
        }
    }

    private static boolean hasAuthorizedTag(AudioAttributes attrs) {
        try { return ((Set<?>) XposedHelpers.callMethod(attrs, "getTags")).contains(AUDIO_TAG); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean headphonesOnly(AudioAttributes attrs) {
        try {
            Class<?> system = XposedHelpers.findClass("android.media.AudioSystem", null);
            List<?> routes = (List<?>) XposedHelpers.callStaticMethod(system, "getDevicesForAttributes", attrs, false);
            if (routes.isEmpty()) return false;
            // A duplicated speaker + headset route stays blocked to prevent speaker leakage.
            for (Object route : routes) {
                int type = (Integer) XposedHelpers.callMethod(route, "getType");
                if (type != AudioDeviceInfo.TYPE_WIRED_HEADSET && type != AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        && type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        && type != AudioDeviceInfo.TYPE_USB_HEADSET && type != AudioDeviceInfo.TYPE_BLE_HEADSET
                        && type != AudioDeviceInfo.TYPE_BLE_SPEAKER && type != AudioDeviceInfo.TYPE_BLE_BROADCAST) return false;
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean connectedCall() {
        if (callStates.containsValue(TelephonyManager.CALL_STATE_OFFHOOK)) return true;
        if (context == null) return false;
        // VoIP audio also works without putting the whole VoIP app on the whitelist.
        int mode = context.getSystemService(AudioManager.class).getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private static boolean allowedRinging() {
        for (Map.Entry<Integer, String> call : ringingNumbers.entrySet()) {
            if (callStates.getOrDefault(call.getKey(), 0) != TelephonyManager.CALL_STATE_RINGING) continue;
            if (policy.numberAllowed(call.getValue())) return true;
            if (context != null) {
                android.telecom.TelecomManager telecom = context.getSystemService(android.telecom.TelecomManager.class);
                if (telecom != null && policy.packages.contains(telecom.getDefaultDialerPackage())) return true;
            }
        }
        return false;
    }

    private static void installCalls(ClassLoader loader) {
        Class<?> c = XposedHelpers.findClass("com.android.server.TelephonyRegistry", loader);
        hookRequired(c, "notifyCallState", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.args.length != 4) return;
                int sub = (Integer) p.args[1], state = (Integer) p.args[2];
                callStates.put(sub, state);
                if (state == TelephonyManager.CALL_STATE_RINGING) ringingNumbers.put(sub, (String) p.args[3]);
                else ringingNumbers.remove(sub);
                notificationDecisions.clear();
                reapply();
            }
        });
        hookRequired(c, "notifyCallStateForAllSubs", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                int state = (Integer) p.args[0];
                callStates.put(-1, state);
                if (state == TelephonyManager.CALL_STATE_RINGING) ringingNumbers.put(-1, (String) p.args[1]);
                else ringingNumbers.remove(-1);
                notificationDecisions.clear();
                reapply();
            }
        });
    }

    private static void installNotifications(ClassLoader loader) {
        Class<?> c = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", loader);
        for (String name : new String[]{"playSound", "playVibration"}) {
            hookRequired(c, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!policy.enabled || notificationAllowed(p.args[0])) return;
                    if (name.equals("playSound")) {
                        AudioAttributes attrs = (AudioAttributes) XposedHelpers.getObjectField(p.args[0], "mAttributes");
                        if (attrs != null && headphonesOnly(attrs)) return;
                    }
                    p.setResult(false);
                }
            });
        }
        // The ringtone player runs in SystemUI, so carry the per-notification decision with it.
        Class<?> record = XposedHelpers.findClass("com.android.server.notification.NotificationRecord", loader);
        hookRequired(record, "getAudioAttributes", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!policy.enabled || p.hasThrowable() || p.getResult() == null) return;
                if (notificationAllowed(p.thisObject)) {
                    AudioAttributes.Builder builder = new AudioAttributes.Builder((AudioAttributes) p.getResult());
                    XposedHelpers.callMethod(builder, "addTag", AUDIO_TAG);
                    p.setResult(builder.build());
                }
            }
        });
        hookRequired(c, "vibrate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (notificationAllowed(p.args[0])) {
                    p.setObjectExtra("authorized", true);
                    notificationVibration.set(notificationVibration.get() + 1);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(p.getObjectExtra("authorized"))) notificationVibration.set(Math.max(0, notificationVibration.get() - 1));
            }
        });
    }

    private static boolean notificationAllowed(Object record) {
        if (!policy.enabled) return true;
        Boolean cached = notificationDecisions.get(record);
        if (cached != null) return cached;
        boolean result = classifyNotification(record);
        notificationDecisions.put(record, result);
        return result;
    }

    private static boolean classifyNotification(Object record) {
        try {
            StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(record, "getSbn");
            if (policy.packages.contains(sbn.getPackageName())) return true;
            // Read the field directly: calling getAudioAttributes here would recurse through its hook.
            AudioAttributes attrs = (AudioAttributes) XposedHelpers.getObjectField(record, "mAttributes");
            if (attrs != null && attrs.getUsage() == AudioAttributes.USAGE_ALARM) return true;
            Notification n = sbn.getNotification();
            if (Notification.CATEGORY_CALL.equals(n.category)) return allowedRinging();
            if (context == null || !sbn.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) return false;
            return smsAllowed(n, sbn.getPostTime());
        } catch (Throwable t) { log("Notification classification failed: " + t.getClass().getSimpleName()); return false; }
    }

    private static boolean smsAllowed(Notification n, long postTime) {
        Bundle extras = n.extras;
        if (extras == null) return false;
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        CharSequence body = null;
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle latest) {
            Person sender = latest.getParcelable("sender_person", Person.class);
            if (sender != null && sender.getUri() != null && sender.getUri().startsWith("tel:")) return policy.numberAllowed(sender.getUri());
            body = latest.getCharSequence("text");
        }
        if (body == null) body = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (body == null || body.length() == 0) return false;
        // Match the exact new message, never a contact nickname or a global temporary SMS exemption.
        long token = Binder.clearCallingIdentity();
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{"address", "date"}, "body = ? AND date >= ? AND date <= ?",
                new String[]{body.toString(), Long.toString(postTime - 120000), Long.toString(postTime + 5000)}, "date DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            do { if (!policy.numberAllowed(cursor.getString(0))) return false; } while (cursor.moveToNext());
            return true;
        } catch (Throwable ignored) { return false; }
        finally { Binder.restoreCallingIdentity(token); }
    }

    private static void installVibration(ClassLoader loader) {
        Class<?> c = XposedHelpers.findClass("com.android.server.vibrator.VibratorManagerService", loader);
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { vibrator = p.thisObject; }
        });
        hookRequired(c, "vibrateInternal", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!policy.enabled) return;
                try {
                    int uid = (Integer) p.args[0];
                    String pkg = (String) p.args[2];
                    VibrationAttributes attrs = (VibrationAttributes) p.args[4];
                    int usage = attrs == null ? 0 : attrs.getUsage();
                    boolean allowed = policy.vibrationAllowed(usage, allowedUids.contains(uid)
                            || policy.packages.contains(pkg), allowedRinging(), notificationVibration.get() > 0);
                    if (!allowed) { blockedVibration.incrementAndGet(); p.setResult(null); }
                } catch (Throwable t) { fail("vibration request", t); }
            }
        });
        Class<?> external = XposedHelpers.findClass("com.android.server.vibrator.VibratorManagerService$ExternalVibratorService", loader);
        hookRequired(external, "onExternalVibrationStart", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!policy.enabled) return;
                Object vib = p.args[0];
                int uid = (Integer) XposedHelpers.callMethod(vib, "getUid");
                String pkg = (String) XposedHelpers.callMethod(vib, "getPackage");
                VibrationAttributes attrs = (VibrationAttributes) XposedHelpers.callMethod(vib, "getVibrationAttributes");
                if (!policy.vibrationAllowed(attrs.getUsage(), allowedUids.contains(uid) || policy.packages.contains(pkg), allowedRinging(), false)) {
                    blockedVibration.incrementAndGet();
                    p.setResult(-100); // IExternalVibratorService.SCALE_MUTE, Android 14
                }
            }
        });
    }

    /** Stop already-running effects when the user enables blocking or removes an exemption. */
    private static void cancelBlockedVibrations() {
        Object service = vibrator;
        if (!policy.enabled || service == null) return;
        try {
            synchronized (XposedHelpers.getObjectField(service, "mLock")) {
                for (String field : new String[]{"mCurrentVibration", "mNextVibration"}) {
                    Object conductor = XposedHelpers.getObjectField(service, field);
                    if (conductor == null) continue;
                    Object vib = XposedHelpers.callMethod(conductor, "getVibration");
                    Object caller = XposedHelpers.getObjectField(vib, "callerInfo");
                    VibrationAttributes attrs = (VibrationAttributes) XposedHelpers.getObjectField(caller, "attrs");
                    int uid = XposedHelpers.getIntField(caller, "uid");
                    String pkg = (String) XposedHelpers.getObjectField(caller, "opPkg");
                    // Existing notification effects have no transferable origin token; preserve them
                    // only when their actual UID/package is allowed or they are an allowed ringtone.
                    if (!policy.vibrationAllowed(attrs.getUsage(), allowedUids.contains(uid)
                            || policy.packages.contains(pkg), allowedRinging(), false)) {
                        Object token = XposedHelpers.getObjectField(vib, "callerToken");
                        XposedHelpers.callMethod(service, "cancelVibrate", attrs.getUsage(), token);
                    }
                }
                Object external = XposedHelpers.getObjectField(service, "mCurrentExternalVibration");
                if (external != null) {
                    Object vib = XposedHelpers.getObjectField(external, "externalVibration");
                    VibrationAttributes attrs = (VibrationAttributes) XposedHelpers.callMethod(vib, "getVibrationAttributes");
                    int uid = (Integer) XposedHelpers.callMethod(vib, "getUid");
                    String pkg = (String) XposedHelpers.callMethod(vib, "getPackage");
                    if (!policy.vibrationAllowed(attrs.getUsage(), allowedUids.contains(uid)
                            || policy.packages.contains(pkg), allowedRinging(), false)) {
                        XposedHelpers.callMethod(service, "cancelVibrate", attrs.getUsage(), null);
                    }
                }
            }
        } catch (Throwable t) { log("Existing vibration cancellation unavailable: " + t.getClass().getSimpleName()); }
    }

    private static void fail(String area, Throwable t) {
        boolean first = error.isEmpty();
        error = area + ": " + t.getClass().getSimpleName();
        policy = Policy.off();
        log(error);
        if (first && handler != null) handler.post(SystemHook::reapply);
    }
    private static void log(String message) { XposedBridge.log(TAG + ": " + message); }
}
