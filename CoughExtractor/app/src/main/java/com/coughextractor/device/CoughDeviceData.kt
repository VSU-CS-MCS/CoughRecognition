package com.coughextractor.device

import java.time.Instant

data class CoughDeviceData(
    /**
     * X Acceleration
     */
    val accelerationX: Int,
    /**
     * Y Acceleration
     */
    val accelerationY: Int,
    /**
     * X Angle
     */
    val angleX: Int,
    /**
     * Y Angle
     */
    val angleY: Int,
    /**
     * Breathing
     */
    val breathing: Int,
    /**
     * Timestamp
     */
    val timestamp: Instant = Instant.now()
)
