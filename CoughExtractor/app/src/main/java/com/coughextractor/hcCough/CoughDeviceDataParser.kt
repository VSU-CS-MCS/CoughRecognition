package com.coughextractor.hcCough

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
            keyValues["Xa"]!!.toInt(),
            keyValues["Ya"]!!.toInt(),
            keyValues["X"]!!.toInt(),
            keyValues["Y"]!!.toInt(),
            keyValues["ADC"]!!.toInt())
    }
}
