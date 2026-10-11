package com.winlator.cmod.feature.stores.common

/** Identifies which store emitted a session event. */
enum class Store {
    EPIC,
    GOG,
    STEAM,
    ITCH,
}

/** Session-lifecycle events that can surface from any store integration to the UI. */
sealed class StoreSessionEvent {
    abstract val store: Store

    /** Stored refresh credentials are dead — user must sign in again. */
    data class SessionExpired(
        override val store: Store,
        val reason: String,
    ) : StoreSessionEvent()

    /** A silent token refresh just succeeded — purely informational (no UI). */
    data class SessionRefreshed(
        override val store: Store,
    ) : StoreSessionEvent()

    /**
     * Credentials were just restored from a Google Play Games cloud snapshot —
     * user-visible so it's clear why they went from "signed out" to "signed in"
     * without an explicit login prompt.
     */
    data class SessionRestored(
        override val store: Store,
    ) : StoreSessionEvent()
}
