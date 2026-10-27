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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * IchthysOS lock screen editor v2 (kusti420/ichthysos-tracker#58): the subject of ANY picture, for
 * the editor's Ideas on a photo that is not the wallpaper yet (MaskProvider only knows the current
 * lock wallpaper).
 *
 * `call("segment", null, extras)` on `content://org.ichthys.depth.segment`: [EXTRA_IMAGE] holds a
 * small encoded picture (the editor sends at most ~640 px on the long side), the result's
 * [EXTRA_MASK] a PNG of the same size with the subject's coverage in the alpha channel (the same
 * mask MaskProvider makes). One at a time; ~4 s on the A52s. Only callers that may read the
 * wallpaper itself (Settings runs as system).
 *
 * IchthysOS Living Sky: `call("sky", ...)` the same way returns the picture's sky ([SkyFinder]) in
 * [EXTRA_MASK] and its subject in [EXTRA_SUBJECT], both PNGs of the picture's size, from one run
 * of the model.
 */
class SegmentProvider : ContentProvider() {

    private val lock = Any()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SEGMENT && method != METHOD_SKY) return null
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(android.Manifest.permission.READ_WALLPAPER_INTERNAL) !=
            PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("segment needs READ_WALLPAPER_INTERNAL")
        }
        val bytes = extras?.getByteArray(EXTRA_IMAGE) ?: return null
        if (bytes.size > MAX_INPUT_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val picture =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val start = System.nanoTime()
            val mask = synchronized(lock) { Segmenter(ctx).mask(picture) }
            val result = Bundle()
            if (method == METHOD_SKY) {
                // The sky at the picture's size, so both masks lie on it the same way.
                val sky = SkyFinder().sky(picture, mask)
                val scaled = Bitmap.createScaledBitmap(sky, picture.width, picture.height, true)
                result.putByteArray(EXTRA_MASK, png(scaled))
                result.putByteArray(EXTRA_SUBJECT, png(mask))
                if (scaled !== sky) scaled.recycle()
                sky.recycle()
            } else {
                result.putByteArray(EXTRA_MASK, png(mask))
            }
            Log.i(TAG, "$method ${picture.width}x${picture.height} in " +
                "${(System.nanoTime() - start) / 1_000_000} ms")
            mask.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "segment failed", e)
            null
        } finally {
            picture.recycle()
        }
    }

    private fun png(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    override fun getType(uri: Uri): String? = null

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
        const val AUTHORITY = "org.ichthys.depth.segment"
        const val METHOD_SEGMENT = "segment"
        const val METHOD_SKY = "sky"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_MASK = "mask"
        const val EXTRA_SUBJECT = "subject"
        /** Long side the picture is decoded at; the model itself looks at 320 x 320. */
        private const val MAX_SIDE = 1024
        private const val MAX_INPUT_BYTES = 900 * 1024
    }
}
