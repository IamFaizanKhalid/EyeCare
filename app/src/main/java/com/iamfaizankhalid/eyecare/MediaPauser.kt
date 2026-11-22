package com.iamfaizankhalid.eyecare

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Pauses any media session that is currently playing. EyeCare never resumes it. */
object MediaPauser {

    fun pausePlaying(context: Context) {
        val component = ComponentName(context, NavigationMonitor::class.java)
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull() ?: return
        sessions.forEach { controller ->
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                runCatching { controller.transportControls.pause() }
            }
        }
    }
}
