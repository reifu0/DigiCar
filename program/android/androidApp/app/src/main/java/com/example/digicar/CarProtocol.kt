package com.example.digicar

import java.util.UUID

/** One control update, in the units the car's firmware expects. */
data class Drive(val speedPercent: Int, val steeringDegrees: Int, val forward: Boolean) {
    /** The firmware treats a `J` speed of 5 or lower as stop. */
    val moving: Boolean get() = speedPercent > CarProtocol.STOP_THRESHOLD_PERCENT

    val command: String
        get() = "J$speedPercent,$steeringDegrees,${if (forward) "F" else "B"}\n"
}

/** Bluetooth protocol of `DigiCar_BT.ino`. */
object CarProtocol {
    const val DEVICE_NAME = "DigiCar_Control"
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val MAX_FORWARD_PERCENT = 100

    /** Full-speed reverse is too fast in practice. Tune this after driving tests. */
    const val MAX_REVERSE_PERCENT = 30

    const val STOP_THRESHOLD_PERCENT = 5
    const val STEERING_CENTER_DEGREES = 90
    const val STEERING_RANGE_DEGREES = 30
    const val MAX_STEERING_TRIM_DEGREES = 15

    /** 0 is linear; higher values make steering gentler near center. Full lock is unaffected. */
    const val STEERING_EXPO = 0.6f

    const val STOP_COMMAND = "S\n"
    const val ACK = "OK"

    /**
     * @param throttle -1 is full reverse, 0 is stop, 1 is full forward
     * @param steering -1 is full left, 0 is straight, 1 is full right
     * @param steeringTrimDegrees moves the straight-ahead position, to correct a car that pulls to one side
     */
    fun drive(throttle: Float, steering: Float, steeringTrimDegrees: Int = 0): Drive {
        val clampedThrottle = throttle.coerceIn(-1f, 1f)
        val forward = clampedThrottle >= 0f
        val speed = if (forward) {
            Math.round(clampedThrottle * MAX_FORWARD_PERCENT)
        } else {
            Math.round(-clampedThrottle * MAX_REVERSE_PERCENT)
        }
        val trim = steeringTrimDegrees.coerceIn(-MAX_STEERING_TRIM_DEGREES, MAX_STEERING_TRIM_DEGREES)
        val shapedSteering = applyExpo(steering.coerceIn(-1f, 1f))
        // The firmware ignores angles outside this range instead of limiting them.
        val angle = (STEERING_CENTER_DEGREES + trim + Math.round(shapedSteering * STEERING_RANGE_DEGREES))
            .coerceIn(
                STEERING_CENTER_DEGREES - STEERING_RANGE_DEGREES,
                STEERING_CENTER_DEGREES + STEERING_RANGE_DEGREES,
            )
        return Drive(speed, angle, forward)
    }

    private fun applyExpo(value: Float) = value * (1f - STEERING_EXPO + STEERING_EXPO * value * value)
}
