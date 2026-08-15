package dev.thedukerchip.scandy.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStateTest {

    @Test
    fun granted_alwaysRequests() {
        assertEquals(PermissionAction.Request, nextAction(isGranted = true, shouldShowRationale = false, hasAsked = false))
        assertEquals(PermissionAction.Request, nextAction(isGranted = true, shouldShowRationale = false, hasAsked = true))
        assertEquals(PermissionAction.Request, nextAction(isGranted = true, shouldShowRationale = true, hasAsked = false))
        assertEquals(PermissionAction.Request, nextAction(isGranted = true, shouldShowRationale = true, hasAsked = true))
    }

    @Test
    fun notGranted_noRationaleNeeded_requests() {
        assertEquals(PermissionAction.Request, nextAction(isGranted = false, shouldShowRationale = false, hasAsked = false))
        assertEquals(PermissionAction.Request, nextAction(isGranted = false, shouldShowRationale = false, hasAsked = true))
    }

    @Test
    fun notGranted_rationaleNeeded_firstAsk_showsRationale() {
        assertEquals(PermissionAction.Rationale, nextAction(isGranted = false, shouldShowRationale = true, hasAsked = false))
    }

    @Test
    fun notGranted_rationaleNeeded_askedAlready_opensSettings() {
        assertEquals(PermissionAction.OpenSettings, nextAction(isGranted = false, shouldShowRationale = true, hasAsked = true))
    }
}
