package com.percy.silentxp;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class PolicyTest {
    private final Policy on = new Policy(true, Set.of("13800138000", "+1 202 555 0123"), Set.of("allowed.app"));
    @Test public void defaultIsUnrestricted() {
        assertTrue(Policy.off().audioAllowed(1, false, false, false, false, false));
        assertTrue(Policy.off().vibrationAllowed(18, false, false, false));
    }
    @Test public void normalizedNumbersMatchExactly() {
        assertTrue(on.numberAllowed("+86 138-0013-8000"));
        assertTrue(on.numberAllowed("008613800138000"));
        assertTrue(on.numberAllowed("tel:+12025550123"));
        assertTrue(on.numberAllowed("0012025550123"));
        assertFalse(on.numberAllowed("13800138001"));
        assertFalse(on.numberAllowed(""));
        assertFalse(on.numberAllowed("2025550123"));
    }
    @Test public void mediaDoesNotLeakDuringCallsOrWhiteListRinging() {
        assertFalse(on.audioAllowed(1, false, false, true, true, false));
        assertTrue(on.audioAllowed(2, false, false, true, false, false));
        assertFalse(on.audioAllowed(2, false, false, false, false, false));
    }
    @Test public void callsRestoreBlockingWhenHungUp() {
        assertTrue(on.audioAllowed(2, false, false, true, false, false));
        assertFalse(on.audioAllowed(2, false, false, false, false, false));
        assertFalse(on.audioAllowed(6, false, false, false, false, false));
        assertTrue(on.audioAllowed(6, false, false, false, true, false));
    }
    @Test public void alarmsHeadphonesAndAppsRemainAudible() {
        assertTrue(on.audioAllowed(4, false, false, false, false, false));
        assertTrue(on.audioAllowed(1, false, true, false, false, false));
        assertTrue(on.audioAllowed(1, true, false, false, false, false));
        assertTrue(on.vibrationAllowed(17, false, false, false));
        assertTrue(on.vibrationAllowed(18, true, false, false));
    }
    @Test public void notificationExemptionIsNotGlobal() {
        assertTrue(on.audioAllowed(5, false, false, false, false, true));
        assertFalse(on.audioAllowed(5, false, false, false, false, false));
        assertFalse(on.audioAllowed(1, false, false, false, false, true));
        assertFalse(on.vibrationAllowed(49, false, false, false));
        assertTrue(on.vibrationAllowed(49, false, false, true));
        assertTrue(on.vibrationAllowed(33, false, true, false));
        assertFalse(on.vibrationAllowed(18, false, true, false));
    }
}
