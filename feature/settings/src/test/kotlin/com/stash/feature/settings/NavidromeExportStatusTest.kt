package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.feature.settings.components.navidromeExportStatus
import org.junit.Test

class NavidromeExportStatusTest {
    @Test
    fun `known result codes have user facing labels`() {
        assertThat(navidromeExportStatus("full_export_queued")?.message)
            .isEqualTo("Full library export is queued")
        assertThat(navidromeExportStatus("export_in_progress")?.message)
            .isEqualTo("Export is running or waiting to retry")
        assertThat(navidromeExportStatus("full_export_complete")?.message)
            .isEqualTo("Full library export completed")
    }

    @Test
    fun `failure status is marked as error`() {
        assertThat(navidromeExportStatus("track_upload_failed")?.isError).isTrue()
        assertThat(navidromeExportStatus("export_incomplete")?.isError).isTrue()
    }

    @Test
    fun `unknown result is not rendered`() {
        assertThat(navidromeExportStatus("future_private_code")).isNull()
    }
}
