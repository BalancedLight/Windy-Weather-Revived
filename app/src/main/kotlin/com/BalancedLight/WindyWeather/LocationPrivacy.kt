package com.BalancedLight.WindyWeather

internal object LocationPrivacy {
    fun roundCoordinate(value: Double): Double = Math.round(value * 100.0) / 100.0
}
