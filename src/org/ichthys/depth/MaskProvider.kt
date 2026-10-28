/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ichthys.depth

import android.app.WallpaperManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * The lock wallpaper's subject masks, for SystemUI:
 * `content://org.ichthys.depth/mask/<userId>/<key>` ([LockWallpaper.key]) opens the PNG mask of
 * that user's current lock wallpaper. While it isn't made yet, opening fails with
 * [FileNotFoundException] and the mask is made in the background; once it is there,
 * `content://org.ichthys.depth/mask/<userId>` is notified and SystemUI opens it again. A key that is
 * not the current wallpaper's any more is refused the same way (the current one is made instead).
 *
 * Masks live in this app's device-protected files (masks/<userId>-<key>.png), so the lock screen
 * has them before the first unlock; a user's older masks go when a new one is written.
 *
 * IchthysOS Living Sky: `content://org.ichthys.depth/sky/<userId>/<key>` is the same for the
 * picture's sky ([SkyFinder], masks/<userId>-<key>-sky.png), notified on
 * `content://org.ichthys.depth/sky/<userId>`. Made only when asked for; it uses the subject mask
 * (made first when it isn't there yet, and kept).
 */
class MaskProvider : ContentProvider() {

    /** One mask at a time: the model takes ~4 cores for a moment. */
    private val worker = Executors.newSingleThreadExecutor()

    /** userId-key pairs being made or queued. */
    private val pending = HashSet<String>()

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val seg = uri.pathSegments
        if (seg.size != 3 || (seg[0] != MASK && seg[0] != SKY)) {
            throw FileNotFoundException("bad uri $uri")
        }
        val sky = seg[0] == SKY
        val userId = seg[1].toIntOrNull() ?: throw FileNotFoundException("bad user $uri")
        val ctx = context ?: throw FileNotFoundException("no context")
        val wm = ctx.getSystemService(WallpaperManager::class.java)
        val current = LockWallpaper.key(wm, userId)
        val file = if (sky) skyFile(userId, current) else maskFile(userId, current)
        if (seg[2] == current && file.isFile) {
            // IchthysOS: an empty file is a write that never finished (from before writes were
            // all or nothing): made again instead of served for good.
            if (file.length() > 0L) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            file.delete()
        }
        make(userId, current, sky)
        throw FileNotFoundException("${seg[0]} for $userId/${seg[2]} not ready")
    }

    /** Makes the subject mask of [key], and with [sky] its sky mask too (whatever is missing). */
    private fun make(userId: Int, key: String, sky: Boolean) {
        val id = "$userId-$key" + if (sky) SKY_SUFFIX else ""
        // IchthysOS: a picture the model failed on is not tried again; every open (SystemUI asks
        // on each lock) would otherwise run it again. A new wallpaper is a new key.
        if (failedFile(userId, key, sky).exists()) return
        synchronized(pending) { if (!pending.add(id)) return }
        worker.execute {
            try {
                val ctx = context ?: return@execute
                val wm = ctx.getSystemService(WallpaperManager::class.java)
                // The wallpaper may have changed again while this waited.
                if (LockWallpaper.key(wm, userId) != key) return@execute
                val maskFile = maskFile(userId, key)
                val skyFile = skyFile(userId, key)
                if (maskFile.isFile && (!sky || skyFile.isFile)) return@execute
                val picture = LockWallpaper.bitmap(wm, userId) ?: return@execute
                // The subject: from its file, else made now (a failed one leaves the sky without).
                var subject = if (maskFile.isFile) BitmapFactory.decodeFile(maskFile.path) else null
                if (subject == null && !failedFile(userId, key, false).exists()) {
                    subject = makeSubject(ctx, userId, key, picture, maskFile)
                }
                if (sky && !skyFile.isFile) {
                    try {
                        val start = System.nanoTime()
                        val finder = SkyFinder()
                        val mask = finder.sky(picture, subject)
                        write(mask, skyFile)
                        Log.i(TAG, "sky $id: ${mask.width}x${mask.height}, upper half " +
                            "${(finder.upperShare * 100).toInt()}% sky, in " +
                            "${(System.nanoTime() - start) / 1_000_000} ms")
                        mask.recycle()
                        clearOthers(userId, key)
                        ctx.contentResolver.notifyChange(skyUri(userId), null)
                    } catch (e: Throwable) {
                        Log.e(TAG, "sky $id failed", e)
                        markFailed(userId, key, true)
                    }
                }
                subject?.recycle()
                picture.recycle()
            } catch (e: Throwable) {
                // A broken wallpaper must not loop: the next wallpaper change retries. Throwable:
                // an OutOfMemoryError or a JNI error would otherwise kill the worker without a
                // marker, and SystemUI asks again (and crashes it again) on every lock.
                Log.e(TAG, "mask $id failed", e)
                markFailed(userId, key, sky)
            } finally {
                synchronized(pending) { pending.remove(id) }
            }
        }
    }

    /** The subject mask of [picture], written to [file]; null (and marked failed) if the model fails. */
    private fun makeSubject(ctx: android.content.Context, userId: Int, key: String,
        picture: Bitmap, file: File): Bitmap? =
        try {
            val start = System.nanoTime()
            val mask = Segmenter(ctx).mask(picture)
            write(mask, file)
            Log.i(TAG, "mask $userId-$key: ${picture.width}x${picture.height} -> " +
                "${mask.width}x${mask.height} in ${(System.nanoTime() - start) / 1_000_000} ms")
            clearOthers(userId, key)
            ctx.contentResolver.notifyChange(userUri(userId), null)
            mask
        } catch (e: Throwable) {
            // A broken model must not loop: the next wallpaper change retries.
            Log.e(TAG, "mask $userId-$key failed", e)
            markFailed(userId, key, false)
            null
        }

    /**
     * [mask] as a PNG at [file], all or nothing: written to a temporary file, synced, then
     * renamed. A half-written or empty PNG (power loss, a full disk) would be served for good
     * and make SystemUI rebuild the lock screen's layers on every lock.
     */
    private fun write(mask: Bitmap, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = FileOutputStream(tmp).use {
            val written = mask.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.fd.sync()
            written
        }
        if (!ok || !tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not write ${file.name}")
        }
    }

    /** The user's files of other wallpapers go (this one's subject, sky and markers stay). */
    private fun clearOthers(userId: Int, key: String) {
        maskFile(userId, key).parentFile?.listFiles()?.forEach {
            val n = it.name
            if (n.startsWith("$userId-") && !n.startsWith("$userId-$key.") &&
                !n.startsWith("$userId-$key-")) {
                it.delete()
            }
        }
    }

    /** Marks [key] as failed (see [make]); only the user's latest failure of a kind is kept. */
    private fun markFailed(userId: Int, key: String, sky: Boolean) {
        try {
            val marker = failedFile(userId, key, sky)
            val suffix = (if (sky) SKY_SUFFIX else "") + FAILED
            marker.parentFile?.listFiles()?.forEach {
                val n = it.name
                val sameKind = n.endsWith(suffix) && (sky || !n.endsWith(SKY_SUFFIX + FAILED))
                if (n.startsWith("$userId-") && sameKind && it != marker) it.delete()
            }
            marker.createNewFile()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "cannot mark $userId-$key failed", e)
        }
    }

    /** Next to the masks, so a later mask of the same user clears it with them. */
    private fun failedFile(userId: Int, key: String, sky: Boolean): File =
        File(maskFile(userId, key).parentFile,
            "$userId-$key" + (if (sky) SKY_SUFFIX else "") + FAILED)

    private fun maskFile(userId: Int, key: String): File {
        // Device-protected: readable before the first unlock (see the manifest).
        val dir = File(context!!.createDeviceProtectedStorageContext().filesDir, "masks")
        dir.mkdirs()
        return File(dir, "$userId-$key.png")
    }

    private fun skyFile(userId: Int, key: String): File =
        File(maskFile(userId, key).parentFile, "$userId-$key$SKY_SUFFIX.png")

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "IchthysDepth"
        private const val FAILED = ".failed"
        private const val MASK = "mask"
        private const val SKY = "sky"
        private const val SKY_SUFFIX = "-sky"
        const val AUTHORITY = "org.ichthys.depth"

        fun userUri(userId: Int): Uri = Uri.parse("content://$AUTHORITY/$MASK/$userId")

        /** IchthysOS Living Sky: notified when a user's sky mask is ready. */
        fun skyUri(userId: Int): Uri = Uri.parse("content://$AUTHORITY/$SKY/$userId")
    }
}
