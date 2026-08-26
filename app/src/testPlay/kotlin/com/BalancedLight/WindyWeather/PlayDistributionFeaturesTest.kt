package com.BalancedLight.WindyWeather

import org.junit.Assert.assertFalse
import org.junit.Test

class PlayDistributionFeaturesTest {
    @Test fun playKeepsRemoteMoonAndExternalSyncDisabled() {
        assertFalse(DistributionFeatures.remoteMoonServiceAvailable)
        assertFalse(DistributionFeatures.externalWeatherSyncAvailable)
    }
}
