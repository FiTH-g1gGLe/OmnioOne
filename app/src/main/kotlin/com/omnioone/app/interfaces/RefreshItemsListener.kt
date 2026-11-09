package com.omnioone.app.interfaces

interface RefreshItemsListener {
    fun refreshItems(invalidate: Boolean = false, callback: (() -> Unit)? = null)
}
