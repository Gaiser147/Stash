package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StoragePreference
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileOrganizerExistingFileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = mockk<StoragePreference> { every { externalTreeUri } returns flowOf<Uri?>(null) }
    private val organizer = FileOrganizer(context, storage)

    private fun libraryFile(path: String, bytes: Int) =
        File(context.filesDir, "music/$path").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }

    @Test fun `finds the committed file in any format, preferring FLAC`() = runTest {
        libraryFile("avicii/singles/levels.opus", 10)
        val flac = libraryFile("avicii/singles/levels.flac", 10)

        assertThat(organizer.findExistingTrackFile("Avicii", null, "Levels")).isEqualTo(flac.absolutePath)
    }

    @Test fun `empty files and other titles don't count, and nothing is created`() = runTest {
        libraryFile("seether/singles/illusion.m4a", 0)
        libraryFile("seether/singles/other-song.m4a", 10)

        assertThat(organizer.findExistingTrackFile("Seether", "", "Illusion")).isNull()
        assertThat(organizer.findExistingTrackFile("Nobody", "Nothing", "Never")).isNull()
        assertThat(File(context.filesDir, "music/nobody").exists()).isFalse()
    }
}
