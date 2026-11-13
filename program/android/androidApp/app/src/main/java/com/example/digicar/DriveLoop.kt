package com.example.digicar

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Sends the current command to the car: promptly when it changes, and as a
 * heartbeat otherwise. Every command replaces the previous one, so a late `OK`
 * doesn't hold up newer controls; replies are used to time the link and to
 * check it is alive. Free of Android classes so it can be tested against a
 * simulated car.
 */
class DriveLoop(
    input: InputStream,
    private val output: OutputStream,
    private val nextCommand: () -> String,
    private val minIntervalMs: Long = 50,
    private val heartbeatMs: Long = 100,
    private val maxUnanswered: Int = 3,
    private val stopAckWaitMs: Long = 500,
    private val linkTimeoutMs: Long = 5_000,
    private val onRoundTrip: (Long) -> Unit = {},
) {
    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val unansweredSendTimesMs = ConcurrentLinkedQueue<Long>()
    private val wake = Semaphore(0)

    @Volatile private var stopRequested = false
    @Volatile private var inputClosed = false
    @Volatile private var lastAckMs = 0L

    /** Makes [run] pick up a new command without waiting for the heartbeat. Safe from any thread. */
    fun controlsChanged() {
        wake.release()
    }

    /** Makes [run] finish by sending the stop command. Safe to call from any thread. */
    fun requestStop() {
        stopRequested = true
        wake.release()
    }

    /**
     * Blocks until [requestStop] has been honoured.
     *
     * @throws IOException if the link fails, in which case the car has not been stopped
     */
    fun run() {
        lastAckMs = nowMs()
        thread(isDaemon = true, name = "DigiCar-ack-reader") { readAcks() }

        var lastCommand: String? = null
        var lastSendMs = nowMs() - heartbeatMs
        while (!stopRequested) {
            // Draining before reading the command means a change made after this line wakes the wait below.
            wake.drainPermits()
            if (inputClosed) throw IOException("Car closed the connection")
            if (nowMs() - lastAckMs > linkTimeoutMs) throw IOException("Car stopped responding")

            val command = nextCommand()
            val intervalMs = if (command != lastCommand) minIntervalMs else heartbeatMs
            val untilDueMs = lastSendMs + intervalMs - nowMs()
            when {
                // Capped so that commands can't queue up and add lag if the car falls behind.
                unansweredSendTimesMs.size >= maxUnanswered -> waitForWake(heartbeatMs)
                untilDueMs > 0 -> waitForWake(untilDueMs)
                else -> {
                    send(command)
                    lastCommand = command
                    lastSendMs = nowMs()
                }
            }
        }
        send(CarProtocol.STOP_COMMAND)
        awaitAllAnswered()
    }

    /** Gives the stop command time to arrive before the caller closes the socket. */
    private fun awaitAllAnswered() {
        val deadlineMs = nowMs() + stopAckWaitMs
        while (unansweredSendTimesMs.isNotEmpty() && !inputClosed && nowMs() < deadlineMs) {
            waitForWake(deadlineMs - nowMs())
        }
    }

    private fun waitForWake(timeoutMs: Long) {
        wake.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun send(command: String) {
        unansweredSendTimesMs.add(nowMs())
        // One write per command: the firmware's line read blocks its main loop on partial lines.
        output.write(command.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readAcks() {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim() == CarProtocol.ACK) recordAck()
            }
        } catch (_: IOException) {
            // Treated the same as end of stream.
        }
        inputClosed = true
        wake.release()
    }

    private fun recordAck() {
        val nowMs = nowMs()
        lastAckMs = nowMs
        unansweredSendTimesMs.poll()?.let { onRoundTrip(nowMs - it) }
        wake.release()
    }

    private fun nowMs() = System.nanoTime() / 1_000_000
}
