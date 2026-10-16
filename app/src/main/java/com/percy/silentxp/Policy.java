package com.percy.silentxp;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Android-independent decision rules, shared by hooks and configuration. */
public final class Policy {
    public final boolean enabled;
    public final Set<String> numbers;
    public final Set<String> packages;

    public Policy(boolean enabled, Set<String> numbers, Set<String> packages) {
        this.enabled = enabled;
        Set<String> clean = new HashSet<>();
        for (String n : numbers) { String value = normalize(n); if (!value.isEmpty()) clean.add(value); }
        this.numbers = Collections.unmodifiableSet(clean);
        this.packages = Collections.unmodifiableSet(new HashSet<>(packages));
    }

    public static Policy off() { return new Policy(false, Set.of(), Set.of()); }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("tel:")) value = value.substring(4);
        // Preserve international country codes; only normalize the Chinese +86 prefix.
        boolean international = value.startsWith("+") || value.startsWith("00");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.startsWith("0086") && digits.length() == 15) return digits.substring(4);
        if (international && digits.startsWith("86") && digits.length() == 13) return digits.substring(2);
        if (value.startsWith("00")) return "+" + digits.substring(2);
        return value.startsWith("+") ? "+" + digits : digits;
    }

    public boolean numberAllowed(String number) {
        String n = normalize(number);
        return !n.isEmpty() && numbers.contains(n);
    }

    public boolean audioAllowed(int usage, boolean appAllowed, boolean headphoneRoute,
                                boolean connectedCall, boolean allowedRinging, boolean taggedNotification) {
        if (!enabled || headphoneRoute || appAllowed || usage == 4) return true; // alarm
        if (usage == 2 || usage == 3) return connectedCall; // voice, call signalling
        if (usage == 6) return allowedRinging || taggedNotification;
        if (usage == 5 || usage == 7 || usage == 8 || usage == 9 || usage == 10) return taggedNotification;
        return false;
    }

    public boolean vibrationAllowed(int usage, boolean appAllowed, boolean allowedRinging,
                                    boolean authorizedNotification) {
        if (!enabled || appAllowed || usage == 17) return true; // VibrationAttributes.USAGE_ALARM
        if (authorizedNotification) return true;
        return usage == 33 && allowedRinging; // USAGE_RINGTONE
    }
}
