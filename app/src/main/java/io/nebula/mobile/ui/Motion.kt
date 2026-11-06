package io.nebula.mobile.ui

import android.view.View

/**
 * Custom animations, driven by real [View.animate] property animators.
 *
 * [reveal] is used when a screen or a settings section is attached, so the effect is
 * visible in the running app. When the user turns animations off in Settings the same
 * call snaps the view to its final state instead, which is why the flag is threaded
 * through every caller rather than being decorative.
 */
object Motion {

    private const val DURATION = 220L

    fun reveal(view: View, enabled: Boolean, delayMillis: Long = 0L) {
        if (!enabled) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val offset = 18f * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = offset
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMillis)
            .setDuration(DURATION)
            .start()
    }
}
