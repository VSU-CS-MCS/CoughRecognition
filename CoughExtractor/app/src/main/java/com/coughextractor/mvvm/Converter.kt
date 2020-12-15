package com.coughextractor.mvvm

import androidx.databinding.InverseMethod

object Converter {
    @InverseMethod("stringToInt")
    fun intToString(value: Int): String {
        return value.toString()
    }

    @InverseMethod("intToString")
    fun stringToInt(value: String): Int {
        return value.toInt()
    }
}
