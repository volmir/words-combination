package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WelcomeRulesTest {
    @Test fun freshInstallWithoutPriorLocalStateGetsShow() {
        assertEquals(
            WelcomeState.SHOW,
            WelcomeRules.resolve(WelcomeState.UNKNOWN, false, InstallEvidence.FRESH_INSTALL),
        )
    }

    @Test fun missingWelcomeKeyOnExistingInstallDoesNotProveFreshInstall() {
        assertEquals(
            WelcomeState.UNKNOWN,
            WelcomeRules.resolve(WelcomeState.UNKNOWN, false, InstallEvidence.EXISTING_INSTALL),
        )
    }

    @Test fun unknownInstallEvidenceFailsClosed() {
        assertEquals(
            WelcomeState.UNKNOWN,
            WelcomeRules.resolve(WelcomeState.UNKNOWN, false, InstallEvidence.UNKNOWN),
        )
    }

    @Test fun anyPriorLocalStateBlocksGreetingEvenOnFreshInstall() {
        assertEquals(
            WelcomeState.UNKNOWN,
            WelcomeRules.resolve(WelcomeState.UNKNOWN, true, InstallEvidence.FRESH_INSTALL),
        )
    }

    @Test fun packageTimestampsProveFreshInstallOnlyWhenEqual() {
        assertEquals(InstallEvidence.FRESH_INSTALL, WelcomeRules.installEvidence(100L, 100L))
        assertEquals(InstallEvidence.EXISTING_INSTALL, WelcomeRules.installEvidence(100L, 101L))
        assertEquals(InstallEvidence.UNKNOWN, WelcomeRules.installEvidence(0L, 0L))
        assertEquals(InstallEvidence.UNKNOWN, WelcomeRules.installEvidence(101L, 100L))
    }

    @Test fun persistedShowAndDoneAreNeverReopenedOrReset() {
        val states = listOf(WelcomeState.SHOW, WelcomeState.DONE)
        val evidence = listOf(
            InstallEvidence.FRESH_INSTALL,
            InstallEvidence.EXISTING_INSTALL,
            InstallEvidence.UNKNOWN,
        )
        for (state in states) {
            for (item in evidence) {
                for (hadLocalState in listOf(true, false)) {
                    assertEquals(state, WelcomeRules.resolve(state, hadLocalState, item))
                }
            }
        }
    }
}
