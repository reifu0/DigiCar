package com.example.digicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProtocolTest {
    private fun command(throttle: Float, steering: Float) = CarProtocol.drive(throttle, steering).command

    @Test
    fun centeredSlidersStopAndGoStraight() {
        assertEquals("J0,90,F\n", command(0f, 0f))
    }

    @Test
    fun forwardReachesFullSpeed() {
        assertEquals("J100,90,F\n", command(1f, 0f))
        assertEquals("J50,90,F\n", command(0.5f, 0f))
    }

    @Test
    fun reverseIsCappedAtThirtyPercent() {
        assertEquals("J30,90,B\n", command(-1f, 0f))
        assertEquals("J15,90,B\n", command(-0.5f, 0f))
    }

    @Test
    fun steeringSpansSixtyToOneTwenty() {
        assertEquals("J0,60,F\n", command(0f, -1f))
        assertEquals("J0,120,F\n", command(0f, 1f))
    }

    @Test
    fun outOfRangeInputIsClamped() {
        assertEquals("J100,120,F\n", command(5f, 5f))
        assertEquals("J30,60,B\n", command(-5f, -5f))
    }

    @Test
    fun steeringIsGentlerNearCenter() {
        assertEquals(98, CarProtocol.drive(0f, 0.5f).steeringDegrees)
        assertEquals(82, CarProtocol.drive(0f, -0.5f).steeringDegrees)
    }

    @Test
    fun trimMovesTheStraightAheadPosition() {
        assertEquals(95, CarProtocol.drive(0f, 0f, steeringTrimDegrees = 5).steeringDegrees)
        assertEquals(85, CarProtocol.drive(0f, 0f, steeringTrimDegrees = -5).steeringDegrees)
    }

    @Test
    fun trimmedSteeringStaysInsideTheFirmwareRange() {
        assertEquals(120, CarProtocol.drive(0f, 1f, steeringTrimDegrees = 10).steeringDegrees)
        assertEquals(60, CarProtocol.drive(0f, -1f, steeringTrimDegrees = -10).steeringDegrees)
    }

    @Test
    fun trimIsLimited() {
        assertEquals(105, CarProtocol.drive(0f, 0f, steeringTrimDegrees = 40).steeringDegrees)
    }

    @Test
    fun speedsAtTheFirmwareStopThresholdAreNotMoving() {
        assertFalse(CarProtocol.drive(0.05f, 0f).moving)
        assertTrue(CarProtocol.drive(0.06f, 0f).moving)
    }
}
