package com.percy.silentxp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

public final class Bridge {
    public static void sync(Context c, Consumer<Bundle> callback) {
        c.sendOrderedBroadcast(new Intent(Config.ACTION).setPackage("android"), null,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        Bundle result = getResultExtras(false);
                        callback.accept(result == null ? Bundle.EMPTY : result);
                    }
                }, new Handler(Looper.getMainLooper()), 0, null, null);
    }
}
