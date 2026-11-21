package com.aura.soundboard.utils

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object UiAnimator {

    private val overshoot = OvershootInterpolator(1.6f)
    private val decelerate = DecelerateInterpolator(1.5f)

    fun bounceTap(view: View?, reducedMotion: Boolean = false) {
        if (view == null || reducedMotion) return
        view.animate().cancel()
        view.scaleX = 0.93f
        view.scaleY = 0.93f
        view.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(150)
            .setInterpolator(overshoot)
            .start()
    }

    fun switchPage(hideView: View, showView: View, slideForward: Boolean, reducedMotion: Boolean = false) {
        if (reducedMotion) {
            hideView.visibility = View.GONE
            showView.alpha = 1f
            showView.translationX = 0f
            showView.visibility = View.VISIBLE
            return
        }

        val offset = if (slideForward) 90f else -90f
        hideView.animate()
            .alpha(0f)
            .translationX(-offset * 0.5f)
            .setDuration(140)
            .withEndAction {
                hideView.visibility = View.GONE
                hideView.alpha = 1f
                hideView.translationX = 0f

                showView.alpha = 0f
                showView.translationX = offset
                showView.visibility = View.VISIBLE
                showView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(190)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()
    }

    fun animateListRow(view: View, reducedMotion: Boolean = false) {
        if (reducedMotion) return
        view.alpha = 0.35f
        view.translationY = 18f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160)
            .setInterpolator(decelerate)
            .start()
    }
}
