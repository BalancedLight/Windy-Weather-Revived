package com.BalancedLight.WindyWeather

import javax.microedition.khronos.opengles.GL

internal interface GLWrapper {
    fun wrap(gl: GL?): GL?
}

