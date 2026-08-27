package com.BalancedLight.WindyWeather

internal object LocationProviderOrder {
    fun active(providers: List<String>): List<String> = providers
        .asSequence()
        .filter { it.isNotBlank() && it != "passive" }
        .distinct()
        .sortedBy {
            when (it) {
                "network" -> 0
                "fused" -> 1
                "gps" -> 2
                else -> 3
            }
        }
        .toList()
}
