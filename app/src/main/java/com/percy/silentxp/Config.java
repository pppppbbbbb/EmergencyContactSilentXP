package com.percy.silentxp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class Config {
    public static final String PKG = "com.percy.silentxp";
    public static final String PERMISSION = PKG + ".CONTROL";
    public static final String ACTION = PKG + ".SYNC";
    public static final android.net.Uri URI = android.net.Uri.parse("content://" + PKG + ".config");
    public static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("policy", Context.MODE_PRIVATE);
    }
    public static Bundle bundle(Context c) {
        SharedPreferences p = prefs(c);
        Bundle b = new Bundle();
        b.putBoolean("enabled", p.getBoolean("enabled", false));
        b.putStringArrayList("numbers", new ArrayList<>(p.getStringSet("numbers", Set.of())));
        b.putStringArrayList("packages", new ArrayList<>(p.getStringSet("packages", Set.of())));
        return b;
    }
    public static Policy policy(Bundle b) {
        if (b == null) throw new IllegalArgumentException("Configuration unavailable");
        return new Policy(b.getBoolean("enabled", false),
                new HashSet<>(list(b, "numbers")), new HashSet<>(list(b, "packages")));
    }
    private static ArrayList<String> list(Bundle b, String key) {
        ArrayList<String> result = b.getStringArrayList(key);
        return result == null ? new ArrayList<>() : result;
    }
    public static void enabled(Context c, boolean value) { prefs(c).edit().putBoolean("enabled", value).commit(); }
}
