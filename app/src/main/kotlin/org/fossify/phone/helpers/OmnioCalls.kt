package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Omnio One call controller: keeps track of the calls Android hands to Omnio and does
 * everything the call screens need (answer, decline, hang up, mute, speaker/Bluetooth,
 * hold, swap, keypad tones). It talks to Android's phone system (android.telecom) directly.
 */
object OmnioCalls {

    /** What the call screen shows. */
    enum class State { RINGING, DIALING, ACTIVE, HOLDING, ENDED }

    enum class Route { EARPIECE, SPEAKER, BLUETOOTH, HEADSET }

    @SuppressLint("StaticFieldLeak")
    private var service: InCallService? = null
    private val calls = CopyOnWriteArrayList<Call>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = notifyChanged()
        override fun onDetailsChanged(call: Call, details: Call.Details) = notifyChanged()
    }

    // ---- fed by the InCallService ----

    fun onCallAdded(inCallService: InCallService, call: Call) {
        service = inCallService
        calls.add(call)
        call.registerCallback(callback)
        notifyChanged()
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls.remove(call)
        if (calls.isEmpty()) service = null
        notifyChanged()
    }

    fun onAudioChanged() = notifyChanged()

    // ---- listening ----

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }

    // ---- reading ----

    fun hasCalls() = calls.isNotEmpty()

    /** The call the screen is about: the ringing one first, then the live one. */
    fun primary(): Call? =
        calls.firstOrNull { stateOf(it) == State.RINGING }
            ?: calls.firstOrNull { stateOf(it) == State.ACTIVE || stateOf(it) == State.DIALING }
            ?: calls.lastOrNull()

    /** The other call when there are two (usually the one on hold). */
    fun secondary(): Call? = calls.firstOrNull { it != primary() }

    fun count() = calls.size

    @Suppress("DEPRECATION")
    private fun rawState(call: Call): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state

    fun stateOf(call: Call?): State {
        if (call == null) return State.ENDED
        return when (rawState(call)) {
            Call.STATE_RINGING, Call.STATE_NEW -> State.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL, Call.STATE_SELECT_PHONE_ACCOUNT -> State.DIALING
            Call.STATE_ACTIVE -> State.ACTIVE
            Call.STATE_HOLDING -> State.HOLDING
            else -> State.ENDED
        }
    }

    fun numberOf(call: Call?): String = call?.details?.handle?.schemeSpecificPart.orEmpty()

    /** When the call connected (ms since boot-independent epoch), or 0 if it hasn't. */
    fun connectedAt(call: Call?): Long = call?.details?.connectTimeMillis ?: 0L

    fun canHold(call: Call?) = call?.details?.can(Call.Details.CAPABILITY_HOLD) == true

    // ---- actions ----

    fun answer() {
        primary()?.takeIf { stateOf(it) == State.RINGING }?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline() {
        primary()?.takeIf { stateOf(it) == State.RINGING }?.reject(false, null)
    }

    /** Ends the call shown on screen (declines it if it's still ringing). */
    fun hangUp() {
        val call = primary() ?: return
        if (stateOf(call) == State.RINGING) call.reject(false, null) else call.disconnect()
    }

    fun toggleHold() {
        val call = primary() ?: return
        when (stateOf(call)) {
            State.HOLDING -> call.unhold()
            State.ACTIVE -> if (canHold(call)) call.hold()
            else -> {}
        }
    }

    /** With two calls: switch to the one on hold. */
    fun swap() {
        secondary()?.takeIf { stateOf(it) == State.HOLDING }?.unhold()
    }

    fun keypadTone(digit: Char) {
        val call = primary() ?: return
        call.playDtmfTone(digit)
        main.postDelayed({ call.stopDtmfTone() }, 150)
    }

    // ---- audio ----

    private fun audioState(): CallAudioState? = service?.callAudioState

    fun isMuted() = audioState()?.isMuted == true

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    fun currentRoute(): Route = routeFor(audioState()?.route ?: CallAudioState.ROUTE_EARPIECE)

    fun availableRoutes(): List<Route> {
        val mask = audioState()?.supportedRouteMask ?: return listOf(Route.EARPIECE, Route.SPEAKER)
        return listOf(
            CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_WIRED_HEADSET,
            CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_SPEAKER
        ).filter { mask and it == it }.map { routeFor(it) }
    }

    fun setRoute(route: Route) {
        service?.setAudioRoute(
            when (route) {
                Route.EARPIECE -> CallAudioState.ROUTE_EARPIECE
                Route.SPEAKER -> CallAudioState.ROUTE_SPEAKER
                Route.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
                Route.HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
            }
        )
    }

    private fun routeFor(value: Int) = when (value) {
        CallAudioState.ROUTE_SPEAKER -> Route.SPEAKER
        CallAudioState.ROUTE_BLUETOOTH -> Route.BLUETOOTH
        CallAudioState.ROUTE_WIRED_HEADSET -> Route.HEADSET
        else -> Route.EARPIECE
    }
}
