package com.stash.data.download.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavidromeRuntimeConstraintsTest {
    @Test
    fun `strict preferences require unmetered network and charging`() {
        assertThat(
            navidromeRuntimeConstraintsMet(
                wifiOnly = true,
                chargingOnly = true,
                networkMetered = false,
                deviceCharging = true,
            ),
        ).isTrue()
        assertThat(
            navidromeRuntimeConstraintsMet(
                wifiOnly = true,
                chargingOnly = true,
                networkMetered = true,
                deviceCharging = true,
            ),
        ).isFalse()
        assertThat(
            navidromeRuntimeConstraintsMet(
                wifiOnly = true,
                chargingOnly = true,
                networkMetered = false,
                deviceCharging = false,
            ),
        ).isFalse()
    }

    @Test
    fun `disabled preferences permit metered network and battery use`() {
        assertThat(
            navidromeRuntimeConstraintsMet(
                wifiOnly = false,
                chargingOnly = false,
                networkMetered = true,
                deviceCharging = false,
            ),
        ).isTrue()
    }
}
