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
import android.graphics.Bitmap

/**
 * The wallpaper the lock screen shows: its own one, else the home screen's (shared, or none set:
 * then the built-in default). SystemUI's IchthysDepthRepository picks it the same way, so a mask
 * made here always belongs to the picture it is drawn over.
 */
object LockWallpaper {
    /**
     * Names one lock wallpaper: both ids, because setting a home wallpaper changes what a shared
     * lock screen shows without touching the lock id. Ids are per user and never reused.
     */
    fun key(wm: WallpaperManager, userId: Int): String =
        "L" + wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId) +
            "S" + wm.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, userId)

    /** The picture itself, software, as stored (cropped); null for a live wallpaper. */
    fun bitmap(wm: WallpaperManager, userId: Int): Bitmap? {
        // IchthysOS: a live wallpaper has no picture. getBitmapAsUser would return the built-in
        // default instead, and a mask of that would be drawn over the live one.
        if (isLive(wm, userId)) return null
        return wm.getBitmapAsUser(userId, false, WallpaperManager.FLAG_LOCK, false)
            ?: wm.getBitmapAsUser(userId, false, WallpaperManager.FLAG_SYSTEM, true)
    }

    /** Whether the lock screen shows a live wallpaper (its own, else the home screen's). */
    fun isLive(wm: WallpaperManager, userId: Int): Boolean {
        val shown =
            if (wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId) > 0)
                WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM
        return wm.getWallpaperInfo(shown, userId) != null
    }
}
