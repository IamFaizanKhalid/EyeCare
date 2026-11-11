package com.iamfaizankhalid.eyecare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores protection after a reboot if the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BreakService.isEnabled(context)) return

        runCatching {
            context.startForegroundService(
                Intent(context, BreakService::class.java)
                    .setAction(BreakService.ACTION_RESUME),
            )
        }
    }
}
