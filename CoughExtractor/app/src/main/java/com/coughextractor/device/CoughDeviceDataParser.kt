package com.coughextractor.device

import java.util.*
import javax.inject.Inject

class CoughDeviceDataParser @Inject constructor() {
    fun parseLine(line: String): CoughDeviceData {
        val keyValues = line
            .split('\t')
            .map {
                val values = it.trim().split('=')
                val key = values[0]
                val value = values[1]
                key to value
            }
            .toMap()

        return CoughDeviceData(
            (keyValues["Xa"] ?: error("Missing Xa")).toInt(),
            (keyValues["Ya"] ?: error("Missing Ya")).toInt(),
            (keyValues["X"] ?: error("Missing X")).toInt(),
            (keyValues["Y"] ?: error("Missing Y")).toInt(),
            (keyValues["ADC"] ?: error("Missing ADC")).toInt())
    }
}
