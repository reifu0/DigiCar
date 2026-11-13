package com.example.digicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DriveLoopTest {
    /** Stands in for the firmware: records each line and answers `OK\r\n`, as `println` does. */
    private class FakeCar(private val ackDelayMs: Long = 0, private val answers: Boolean = true) {
        val received = CopyOnWriteArrayList<String>()
        val writeCalls = CopyOnWriteArrayList<ByteArray>()
        private val toApp = LinkedBlockingQueue<Int>()

        val driveCommands get() = received.filter { it.startsWith("J") }

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                writeCalls += b.copyOfRange(off, off + len)
                received += String(b, off, len, Charsets.UTF_8)
                if (answers) {
                    thread(isDaemon = true) {
                        Thread.sleep(ackDelayMs)
                        "OK\r\n".forEach { toApp.put(it.code) }
                    }
                }
            }
        }

        val input = object : InputStream() {
            override fun read(): Int = toApp.take()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val next = read()
                if (next == EOF) return EOF
                b[off] = next.toByte()
                return 1
            }
        }

        fun hangUp() = toApp.put(EOF)
    }

    private fun start(loop: DriveLoop) = thread { loop.run() }

    private fun finish(loop: DriveLoop, runner: Thread) {
        loop.requestStop()
        runner.join(3_000)
        assertTrue("loop did not finish", !runner.isAlive)
    }

    private fun runFor(ms: Long, loop: DriveLoop) {
        val runner = start(loop)
        Thread.sleep(ms)
        finish(loop, runner)
    }

    @Test
    fun heartbeatSendsAboutTenCommandsPerSecondWhenIdle() {
        val car = FakeCar()
        runFor(1_000, DriveLoop(car.input, car.output, { "J0,90,F\n" }))

        val drives = car.driveCommands.size
        assertTrue("sent $drives commands in 1 s", drives in 8..11)
    }

    @Test
    fun changedControlsAreSentWithoutWaitingForTheHeartbeat() {
        val car = FakeCar()
        val current = AtomicReference("J10,90,F\n")
        val loop = DriveLoop(car.input, car.output, { current.get() }, minIntervalMs = 20, heartbeatMs = 5_000)
        val runner = start(loop)
        Thread.sleep(200)

        current.set("J20,100,F\n")
        loop.controlsChanged()
        Thread.sleep(300)

        assertEquals(listOf("J10,90,F\n", "J20,100,F\n"), car.driveCommands)
        finish(loop, runner)
    }

    @Test
    fun rapidChangesAreLimitedToTheMinimumInterval() {
        val car = FakeCar()
        val counter = AtomicInteger()
        runFor(1_000, DriveLoop(car.input, car.output, { "J${counter.incrementAndGet()},90,F\n" }))

        val drives = car.driveCommands.size
        assertTrue("sent $drives commands in 1 s", drives in 12..21)
    }

    @Test
    fun lateOkDoesNotHoldBackTheNewestCommand() {
        val car = FakeCar(ackDelayMs = 500)
        val current = AtomicReference("J10,90,F\n")
        val loop = DriveLoop(car.input, car.output, { current.get() }, heartbeatMs = 5_000)
        val runner = start(loop)
        Thread.sleep(100)

        current.set("J20,100,F\n")
        loop.controlsChanged()
        Thread.sleep(150)

        assertTrue("newest command waited for the OK", car.received.contains("J20,100,F\n"))
        finish(loop, runner)
    }

    @Test
    fun stopsSendingWhenTooManyCommandsAreUnanswered() {
        val car = FakeCar(answers = false)
        val counter = AtomicInteger()
        val loop = DriveLoop(
            car.input,
            car.output,
            { "J${counter.incrementAndGet()},90,F\n" },
            minIntervalMs = 10,
            heartbeatMs = 20,
            maxUnanswered = 3,
            stopAckWaitMs = 50,
            linkTimeoutMs = 10_000,
        )
        val runner = start(loop)
        Thread.sleep(400)

        assertEquals(3, car.driveCommands.size)
        finish(loop, runner)
        assertEquals("the stop command must bypass the limit", "S\n", car.received.last())
    }

    @Test
    fun alwaysSendsTheLatestControls() {
        val car = FakeCar()
        val current = AtomicReference("J10,90,F\n")
        val loop = DriveLoop(car.input, car.output, { current.get() })
        val runner = start(loop)
        Thread.sleep(250)
        current.set("J20,100,F\n")
        Thread.sleep(250)
        finish(loop, runner)

        assertEquals("J10,90,F\n", car.received.first())
        assertTrue(car.received.contains("J20,100,F\n"))
    }

    @Test
    fun stopRequestEndsWithTheStopCommand() {
        val car = FakeCar()
        runFor(250, DriveLoop(car.input, car.output, { "J50,90,F\n" }))

        assertEquals("S\n", car.received.last())
    }

    @Test
    fun eachCommandIsASingleCompleteWrite() {
        val car = FakeCar()
        runFor(250, DriveLoop(car.input, car.output, { "J50,90,F\n" }))

        assertTrue(car.writeCalls.isNotEmpty())
        car.writeCalls.forEach { assertTrue(String(it).endsWith("\n")) }
    }

    @Test
    fun reportsTheTimeFromCommandToOk() {
        val car = FakeCar(ackDelayMs = 100)
        val roundTrips = CopyOnWriteArrayList<Long>()
        val loop = DriveLoop(car.input, car.output, { "J0,90,F\n" }, onRoundTrip = { roundTrips += it })
        runFor(500, loop)

        assertTrue(roundTrips.isNotEmpty())
        roundTrips.forEach { assertTrue("round trip was $it ms", it in 99..400) }
    }

    @Test
    fun silentCarIsReportedAsLinkFailure() {
        val car = FakeCar(answers = false)
        val loop = DriveLoop(car.input, car.output, { "J0,90,F\n" }, linkTimeoutMs = 200)

        assertThrows(IOException::class.java) { loop.run() }
    }

    @Test
    fun droppedConnectionIsReportedPromptly() {
        val car = FakeCar()
        val loop = DriveLoop(car.input, car.output, { "J0,90,F\n" })
        thread(isDaemon = true) {
            Thread.sleep(200)
            car.hangUp()
        }

        val startedMs = System.currentTimeMillis()
        assertThrows(IOException::class.java) { loop.run() }
        assertTrue(System.currentTimeMillis() - startedMs < 2_000)
    }

    private companion object {
        const val EOF = -1
    }
}
