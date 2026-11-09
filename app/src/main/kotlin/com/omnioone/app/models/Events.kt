package com.omnioone.app.models

sealed class Events {
    data object RefreshCallLog : Events()
}
