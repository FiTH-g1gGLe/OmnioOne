package com.omnioone.app.extensions

import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.runAfterAnimations(callback: () -> Unit) {
    if (isComputingLayout) {
        post { runAfterAnimations(callback) }
        return
    }

    val animator = itemAnimator
    if (animator == null) {
        post(callback)
    } else {
        animator.isRunning {
            post(callback)
        }
    }
}
