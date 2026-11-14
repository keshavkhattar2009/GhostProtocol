package com.example.ghostprotocol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires the moment the user dismisses the system lock screen (enters fingerprint / PIN / swipe).
 * Android sends ACTION_USER_PRESENT for this exact event.
 *
 * We immediately launch MainActivity and tag it with KEY_FROM_UNLOCK = true so the app
 * knows it was triggered by a phone unlock (not a manual launcher tap).
 *
 * NOTE: ACTION_USER_PRESENT cannot be received via a static <receiver> tag on API 26+.
 *       It MUST be registered dynamically (see MainActivity.onCreate).
 */
public class KeyguardDismissReceiver extends BroadcastReceiver {

    static final String KEY_FROM_UNLOCK = "from_unlock";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;

        Intent launch = new Intent(context, MainActivity.class);
        launch.putExtra(KEY_FROM_UNLOCK, true);
        launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(launch);
    }
}
