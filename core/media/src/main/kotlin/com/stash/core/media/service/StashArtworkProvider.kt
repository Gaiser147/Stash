package com.stash.core.media.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stash.core.data.db.dao.TrackDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves local album covers to Android Auto, which renders in its own process
 * and can't open the app's file paths. `content://<authority>/track/<id>`
 * maps to that track's `albumArtPath` — never an arbitrary path — and only
 * files inside the app's own storage are served, read-only.
 *
 * Not exported: the browse callback grants read access per URI to the
 * connected car client ([grantTo]).
 */
class StashArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun trackDao(): TrackDao
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val trackId = trackIdOf(uri) ?: throw FileNotFoundException(uri.toString())
        val dao = EntryPointAccessors.fromApplication(ctx.applicationContext, Deps::class.java).trackDao()
        val path = runBlocking { dao.getById(trackId)?.albumArtPath }
            ?: throw FileNotFoundException(uri.toString())
        val file = File(path.removePrefix("file://")).canonicalFile
        if (!file.isFile || !isAppStorage(ctx, file)) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TRACK_SEGMENT = "track"

        /** The authority this app registers (see the core:media manifest). */
        fun authority(context: Context): String = "${context.packageName}.autoart"

        fun trackArtUri(authority: String, trackId: Long): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(TRACK_SEGMENT).appendPath(trackId.toString()).build()

        internal fun trackIdOf(uri: Uri): Long? {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != TRACK_SEGMENT) return null
            return segments[1].toLongOrNull()
        }

        /** Lets [packageName] (the car) read the covers among [uris]. */
        fun grantTo(context: Context, packageName: String, uris: Iterable<Uri?>) {
            val authority = authority(context)
            for (uri in uris) {
                if (uri?.authority != authority) continue
                runCatching {
                    context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        private fun isAppStorage(context: Context, file: File): Boolean {
            val roots = listOfNotNull(
                context.filesDir?.parentFile,
                context.cacheDir,
                context.getExternalFilesDir(null)?.parentFile,
                context.externalCacheDir,
            ).map { it.canonicalPath + File.separator }
            return roots.any { file.path.startsWith(it) }
        }
    }
}
