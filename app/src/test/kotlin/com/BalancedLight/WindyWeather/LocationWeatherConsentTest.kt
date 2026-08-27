package com.BalancedLight.WindyWeather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationWeatherConsentTest {
    @Test fun existingPermissionDoesNotMigrateIntoConsent() {
        assertFalse(LocationWeatherConsent.isTransferAllowed(0, true))
    }

    @Test fun transferRequiresCurrentConsentAndCoarsePermission() {
        assertTrue(LocationWeatherConsent.isTransferAllowed(LocationWeatherConsent.CURRENT_VERSION, true))
        assertFalse(LocationWeatherConsent.isTransferAllowed(LocationWeatherConsent.CURRENT_VERSION, false))
        assertFalse(LocationWeatherConsent.isTransferAllowed(LocationWeatherConsent.CURRENT_VERSION - 1, true))
    }

    @Test fun versionOneConsentIsInvalidatedByExpandedRecipientDisclosure() {
        assertEquals(2, LocationWeatherConsent.CURRENT_VERSION)
        assertFalse(LocationWeatherConsent.isVersionAccepted(1))
        assertFalse(LocationWeatherConsent.isTransferAllowed(1, true))
    }

    @Test fun supersededConsentIsDistinguishedFromNeverHavingConsented() {
        assertTrue(LocationWeatherConsent.needsReconsent(1))
        assertFalse(LocationWeatherConsent.needsReconsent(0))
        assertFalse(LocationWeatherConsent.needsReconsent(LocationWeatherConsent.CURRENT_VERSION))
    }

    @Test fun denialAndRevocationReturnToUnapprovedState() {
        assertEquals(0, LocationWeatherConsent.versionAfterPermissionResult(false))
        assertFalse(LocationWeatherConsent.isVersionAccepted(0))
    }
}
