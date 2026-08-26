package com.BalancedLight.WindyWeather

import org.junit.Assert.assertTrue
import org.junit.Test

class GithubDistributionFeaturesTest {
    @Test fun githubRetainsRemoteMoonAndExternalSync() {
        assertTrue(DistributionFeatures.remoteMoonServiceAvailable)
        assertTrue(DistributionFeatures.externalWeatherSyncAvailable)
    }
}
