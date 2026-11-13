package com.example.digicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

sealed interface LinkState {
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Disconnected(val problem: Problem? = null) : LinkState

    enum class Problem { CONNECT_FAILED, LINK_LOST }
}

/**
 * Owns the Bluetooth connection to the car. Use this class on the main thread;
 * [onState] and [onRoundTrip] are also delivered there. The caller must hold
 * the Bluetooth permission before calling [connect].
 */
class CarLink(
    private val onState: (LinkState) -> Unit,
    private val onRoundTrip: (RoundTripSummary) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null

    @Volatile var throttle = 0f
        set(value) {
            field = value
            session?.controlsChanged()
        }

    @Volatile var steering = 0f
        set(value) {
            field = value
            session?.controlsChanged()
        }

    @Volatile var steeringTrimDegrees = 0
        set(value) {
            field = value
            session?.controlsChanged()
        }

    fun connect(device: BluetoothDevice) {
        if (session != null) return
        throttle = 0f
        steering = 0f
        session = Session(device).also { it.start() }
        onState(LinkState.Connecting)
    }

    /** Stops the car, then closes the connection. */
    fun disconnect() {
        session?.close()
    }

    @SuppressLint("MissingPermission")
    private inner class Session(private val device: BluetoothDevice) : Thread("DigiCar-link") {
        @Volatile private var closing = false
        @Volatile private var socket: BluetoothSocket? = null
        @Volatile private var loop: DriveLoop? = null

        fun controlsChanged() {
            loop?.controlsChanged()
        }

        fun close() {
            closing = true
            val activeLoop = loop
            if (activeLoop != null) activeLoop.requestStop() else closeSocket()
        }

        override fun run() {
            var connected = false
            var finalState = LinkState.Disconnected()
            try {
                val newSocket = device.createRfcommSocketToServiceRecord(CarProtocol.SPP_UUID)
                socket = newSocket
                if (closing) return
                newSocket.connect()

                val stats = RoundTripStats()
                val newLoop = DriveLoop(
                    newSocket.inputStream,
                    newSocket.outputStream,
                    nextCommand = { CarProtocol.drive(throttle, steering, steeringTrimDegrees).command },
                    onRoundTrip = { roundTripMs -> report(stats, roundTripMs) },
                )
                loop = newLoop
                if (closing) newLoop.requestStop()
                connected = true
                mainHandler.post { onState(LinkState.Connected) }
                newLoop.run()
            } catch (_: IOException) {
                finalState = disconnectedAfterFailure(connected)
            } catch (_: SecurityException) {
                finalState = disconnectedAfterFailure(connected)
            } finally {
                closeSocket()
                mainHandler.post {
                    session = null
                    onState(finalState)
                }
            }
        }

        private fun report(stats: RoundTripStats, roundTripMs: Long) {
            val summary = stats.add(roundTripMs, System.nanoTime() / 1_000_000) ?: return
            Log.d(TAG, "Round trip: average ${summary.averageMs} ms, worst ${summary.worstMs} ms")
            mainHandler.post { onRoundTrip(summary) }
        }

        private fun disconnectedAfterFailure(connected: Boolean) = when {
            // Even during a requested disconnect, a failure here means the stop may not have landed.
            connected -> LinkState.Disconnected(LinkState.Problem.LINK_LOST)
            closing -> LinkState.Disconnected()
            else -> LinkState.Disconnected(LinkState.Problem.CONNECT_FAILED)
        }

        private fun closeSocket() {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }

    private companion object {
        const val TAG = "DigiCar"
    }
}
