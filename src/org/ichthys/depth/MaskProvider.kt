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
        if (seg.size != 3 || seg[0] != "mask") throw FileNotFoundException("bad uri $uri")
        val userId = seg[1].toIntOrNull() ?: throw FileNotFoundException("bad user $uri")
        val ctx = context ?: throw FileNotFoundException("no context")
        val wm = ctx.getSystemService(WallpaperManager::class.java)
        val current = LockWallpaper.key(wm, userId)
        val file = maskFile(userId, current)
        if (seg[2] == current && file.isFile) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        make(userId, current)
        throw FileNotFoundException("mask for $userId/${seg[2]} not ready")
    }

    private fun make(userId: Int, key: String) {
        val id = "$userId-$key"
        synchronized(pending) { if (!pending.add(id)) return }
        worker.execute {
            try {
                val ctx = context ?: return@execute
                val wm = ctx.getSystemService(WallpaperManager::class.java)
                // The wallpaper may have changed again while this waited.
                if (LockWallpaper.key(wm, userId) != key) return@execute
                val picture = LockWallpaper.bitmap(wm, userId) ?: return@execute
                val start = System.nanoTime()
                val mask = Segmenter(ctx).mask(picture)
                val file = maskFile(userId, key)
                val tmp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(tmp).use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(file)
                Log.i(TAG, "mask $id: ${picture.width}x${picture.height} -> " +
                    "${mask.width}x${mask.height} in ${(System.nanoTime() - start) / 1_000_000} ms")
                mask.recycle()
                picture.recycle()
                file.parentFile?.listFiles()?.forEach {
                    if (it.name.startsWith("$userId-") && it != file) it.delete()
                }
                ctx.contentResolver.notifyChange(userUri(userId), null)
            } catch (e: Exception) {
                // A broken wallpaper or model must not loop: the next wallpaper change retries.
                Log.e(TAG, "mask $id failed", e)
            } finally {
                synchronized(pending) { pending.remove(id) }
            }
        }
    }

    private fun maskFile(userId: Int, key: String): File {
        // Device-protected: readable before the first unlock (see the manifest).
        val dir = File(context!!.createDeviceProtectedStorageContext().filesDir, "masks")
        dir.mkdirs()
        return File(dir, "$userId-$key.png")
    }

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
        const val AUTHORITY = "org.ichthys.depth"

        fun userUri(userId: Int): Uri = Uri.parse("content://$AUTHORITY/mask/$userId")
    }
}
