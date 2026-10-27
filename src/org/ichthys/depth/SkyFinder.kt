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

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * IchthysOS Living Sky: finds the sky of any picture, without a model (none fits on the phone
 * next to U²-Netp). The result is a mask like [Segmenter]'s: white, the sky's coverage in the alpha
 * channel, the picture's aspect ratio, at most [OUT_SIDE] px on its long side.
 *
 * Three passes, coarse to fine:
 * 1. Analysis ([ANALYSIS_SIDE] px): every pixel gets a sky score: a sky colour (blue, overcast
 *    white or grey, sunrise and sunset hues), smooth (little fine texture, measured at the guide
 *    size), high in the frame, and not the subject ([Segmenter]'s mask, when there is one).
 * 2. The sky is what is connected to the top edge: grown from the top rows into neighbours that
 *    score and change colour gently (a ridge or a tree line is a step), against a reference colour
 *    that follows the sky's own gradient. Holes in it (clouds, birds, the sun) are filled; gaps of
 *    the same sky elsewhere (seen through branches, between buildings) are taken in.
 * 3. Edges: a colour guided filter (He et al., "Guided Image Filtering"; the fast form: its
 *    coefficients at [GUIDE_SIDE], applied at full size) moves the coarse edge onto the picture's
 *    own, so leaves, ridges and roofs against the sky get a soft, exact matte and no halo when the
 *    sky is regraded. A gate keeps it from leaking far from the coarse sky.
 *
 * ~0.3 s for a phone wallpaper on the A52s; off the main thread only.
 */
class SkyFinder {

    /** What the last [sky] found: the sky's share of the picture's upper half (0..1). */
    var upperShare = 0f
        private set

    fun sky(picture: Bitmap, subject: Bitmap?): Bitmap {
        val src = picture.toSoftware()
        val longSide = max(src.width, src.height)
        val outK = min(1f, OUT_SIDE.toFloat() / longSide)
        val ow = max(8, (src.width * outK).roundToInt())
        val oh = max(8, (src.height * outK).roundToInt())
        val full = scaleDown(src, ow, oh)
        if (src !== picture && src !== full) src.recycle()

        val gk = min(1f, GUIDE_SIDE.toFloat() / max(ow, oh))
        val gw = max(8, (ow * gk).roundToInt())
        val gh = max(8, (oh * gk).roundToInt())
        val guideBmp = scaleDown(full, gw, gh)
        val ak = min(1f, ANALYSIS_SIDE.toFloat() / max(gw, gh))
        val aw = max(8, (gw * ak).roundToInt())
        val ah = max(8, (gh * ak).roundToInt())
        val analysisBmp = scaleDown(guideBmp, aw, ah)

        val gPx = IntArray(gw * gh).also { guideBmp.getPixels(it, 0, gw, 0, 0, gw, gh) }
        val aPx = IntArray(aw * ah).also { analysisBmp.getPixels(it, 0, aw, 0, 0, aw, ah) }
        if (analysisBmp !== full && analysisBmp !== guideBmp) analysisBmp.recycle()
        if (guideBmp !== full) guideBmp.recycle()

        val texture = texture(gPx, gw, gh, aw, ah)
        val subj = subject?.let { alphaAt(it, aw, ah) }
        val coarse = coarseSky(aPx, aw, ah, texture, subj)

        val n = aw * ah
        var upper = 0f
        var upperN = 0
        for (y in 0 until ah / 2) for (x in 0 until aw) {
            upper += coarse[y * aw + x]
            upperN++
        }
        upperShare = if (upperN > 0) upper / upperN else 0f

        // The guided filter's input: the coarse sky, softened by a pixel so it upsamples smoothly;
        // the gate (where the sky may reach) and the floor (where it surely is).
        val soft = FloatArray(n)
        val tmp = FloatArray(n)
        box(coarse, aw, ah, 1, tmp, soft)
        val gate = dilate(coarse, aw, ah, GATE_PX)
        box(gate.copyOf(), aw, ah, 1, tmp, gate)
        val floor = erode(coarse, aw, ah, FLOOR_PX)
        box(floor.copyOf(), aw, ah, 1, tmp, floor)

        val coef = guided(gPx, gw, gh, upsample(soft, aw, ah, gw, gh))

        val mask = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val row = IntArray(ow)
        val sxG = gw.toFloat() / ow
        val syG = gh.toFloat() / oh
        val sxA = aw.toFloat() / ow
        val syA = ah.toFloat() / oh
        for (y in 0 until oh) {
            full.getPixels(row, 0, ow, 0, y, ow, 1)
            val gy = (y + 0.5f) * syG - 0.5f
            val ay = (y + 0.5f) * syA - 0.5f
            for (x in 0 until ow) {
                val c = row[x]
                val gx = (x + 0.5f) * sxG - 0.5f
                val ax = (x + 0.5f) * sxA - 0.5f
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                var q =
                    sample(coef[0], gw, gh, gx, gy) * r + sample(coef[1], gw, gh, gx, gy) * g +
                        sample(coef[2], gw, gh, gx, gy) * b + sample(coef[3], gw, gh, gx, gy)
                // A touch of contrast: the matte's long tails would grey the edge either way.
                q = ((q - 0.04f) / 0.92f).coerceIn(0f, 1f)
                q *= sample(gate, aw, ah, ax, ay).coerceIn(0f, 1f)
                q = max(q, sample(floor, aw, ah, ax, ay).coerceIn(0f, 1f))
                row[x] = Color.argb((q * 255f).roundToInt(), 255, 255, 255)
            }
            mask.setPixels(row, 0, ow, 0, y, ow, 1)
        }
        if (full !== picture) full.recycle()
        return mask
    }

    // ---- 1 + 2: the coarse sky -----------------------------------------------------------------

    private fun coarseSky(px: IntArray, w: Int, h: Int, texture: FloatArray, subj: FloatArray?): FloatArray {
        val n = w * h
        val cr = FloatArray(n)
        val cg = FloatArray(n)
        val cb = FloatArray(n)
        // skyish: colour and smoothness only; score: also placed like a sky and not the subject.
        val skyish = FloatArray(n)
        val score = FloatArray(n)
        for (i in 0 until n) {
            val r = Color.red(px[i]) / 255f
            val g = Color.green(px[i]) / 255f
            val b = Color.blue(px[i]) / 255f
            cr[i] = r
            cg[i] = g
            cb[i] = b
            val y = i / w
            val colour = skyColour(r, g, b)
            // IchthysOS: clouds have texture; high in the frame (where a sky is) more of it is
            // still sky, so sunset cloudscapes and broken cloud aren't cut out.
            val top = 1f - y.toFloat() / h
            val texK = 1f + 2.2f * smoothstep(0.35f, 0.85f, top)
            val smooth = 1f - smoothstep(TEX_LOW * texK, TEX_HIGH * texK, texture[i])
            skyish[i] = colour * smooth
            val pos = 1f - smoothstep(0.5f, 0.95f, y.toFloat() / h)
            val notSubject = if (subj == null) 1f else 1f - smoothstep(0.25f, 0.6f, subj[i])
            score[i] = skyish[i] * pos * notSubject
        }

        // Grown from the top rows. ref*: the colour the sky "should" have here, carried along.
        val sky = FloatArray(n)
        val refR = FloatArray(n)
        val refG = FloatArray(n)
        val refB = FloatArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        val seedRows = max(1, h / 40)
        for (y in 0 until seedRows) for (x in 0 until w) {
            val i = y * w + x
            if (score[i] > SEED) {
                sky[i] = 1f
                refR[i] = cr[i]
                refG[i] = cg[i]
                refB[i] = cb[i]
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val p = queue[head++]
            val px0 = p % w
            val py0 = p / w
            for (k in 0 until 4) {
                val qx = px0 + DX[k]
                val qy = py0 + DY[k]
                if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                val q = qy * w + qx
                if (sky[q] > 0f) continue
                val s = score[q]
                if (s < GROW) continue
                val step = dist(cr[p], cg[p], cb[p], cr[q], cg[q], cb[q])
                val drift = dist(refR[p], refG[p], refB[p], cr[q], cg[q], cb[q])
                // Cloud edges are big colour steps inside the sky: allowed more high up.
                val loose = 1f + 0.8f * smoothstep(0.4f, 0.85f, 1f - qy.toFloat() / h)
                val ok = (step < STEP * loose && drift < DRIFT * loose) ||
                    (s > STRONG && step < STEP_STRONG * loose)
                if (!ok) continue
                sky[q] = 1f
                refR[q] = refR[p] + (cr[q] - refR[p]) * REF_RATE
                refG[q] = refG[p] + (cg[q] - refG[p]) * REF_RATE
                refB[q] = refB[p] + (cb[q] - refB[p]) * REF_RATE
                queue[tail++] = q
            }
        }
        if (tail == 0) return sky

        fillHoles(sky, w, h, skyish, subj)
        adoptIslands(sky, w, h, score, cr, cg, cb)
        return sky
    }

    /**
     * Non-sky pieces inside the sky (clouds the colour test missed, birds, the sun, a contrail)
     * become sky: small, not the subject, not reaching the bottom; touching a side or the top only
     * when they look like sky themselves (a cloud cut by the frame, not a branch coming in).
     */
    private fun fillHoles(sky: FloatArray, w: Int, h: Int, skyish: FloatArray, subj: FloatArray?) {
        val n = w * h
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        val members = IntArray(n)
        for (start in 0 until n) {
            if (sky[start] > 0f || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = start
            var touchesBottom = false
            var touchesEdge = false
            var sumSkyish = 0f
            var sumSubj = 0f
            var sumY = 0L
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                members[count++] = p
                val x = p % w
                val y = p / w
                if (y == h - 1) touchesBottom = true
                if (x == 0 || x == w - 1 || y == 0) touchesEdge = true
                sumSkyish += skyish[p]
                sumY += y
                if (subj != null) sumSubj += subj[p]
                for (k in 0 until 4) {
                    val qx = x + DX[k]
                    val qy = y + DY[k]
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (sky[q] > 0f || label[q] >= 0) continue
                    label[q] = start
                    queue[tail++] = q
                }
            }
            val small = count < n * HOLE_SHARE
            val notSubject = subj == null || sumSubj / count < 0.35f
            val looksSky = sumSkyish / count > 0.3f
            // IchthysOS: a piece the sky closes all round is a cloud or the sun even when the
            // subject finder picked it (it does pick a lone cloud in an empty sky).
            val enclosed = !touchesEdge && !touchesBottom && count < n * HOLE_SHARE * 0.5f
            // A big band of cloud high in the frame (a sunset's lit undersides) that looks like
            // sky, even where it runs off the side.
            val cloudBand = !touchesBottom && notSubject && sumSkyish / count > 0.38f &&
                count < n * 0.2f && sumY / count < h * 0.42f
            if (enclosed || cloudBand ||
                (!touchesBottom && small && notSubject && (!touchesEdge || looksSky))) {
                for (i in 0 until count) sky[members[i]] = 1f
            }
        }
    }

    /**
     * Sky not connected to the top (between branches, under an arch): pieces that score as sky and
     * have the colour the connected sky has at their height.
     */
    private fun adoptIslands(sky: FloatArray, w: Int, h: Int, score: FloatArray,
        cr: FloatArray, cg: FloatArray, cb: FloatArray) {
        // The connected sky's mean colour per row (rows without sky borrow the nearest above).
        val rowR = FloatArray(h)
        val rowG = FloatArray(h)
        val rowB = FloatArray(h)
        val rowN = IntArray(h)
        for (i in sky.indices) if (sky[i] > 0f) {
            val y = i / w
            rowR[y] += cr[i]
            rowG[y] += cg[i]
            rowB[y] += cb[i]
            rowN[y]++
        }
        var lastY = -1
        for (y in 0 until h) {
            if (rowN[y] > 0) {
                rowR[y] /= rowN[y]
                rowG[y] /= rowN[y]
                rowB[y] /= rowN[y]
                lastY = y
            } else if (lastY >= 0) {
                rowR[y] = rowR[lastY]
                rowG[y] = rowG[lastY]
                rowB[y] = rowB[lastY]
            } else {
                rowR[y] = -1f
            }
        }
        val n = w * h
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        for (start in 0 until n) {
            if (sky[start] > 0f || seen[start] || score[start] < GROW) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var ss = 0f
            var sy = 0
            while (head < tail) {
                val p = queue[head++]
                sr += cr[p]
                sg += cg[p]
                sb += cb[p]
                ss += score[p]
                sy += p / w
                val x = p % w
                val y = p / w
                for (k in 0 until 4) {
                    val qx = x + DX[k]
                    val qy = y + DY[k]
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (sky[q] > 0f || seen[q] || score[q] < GROW) continue
                    if (dist(cr[p], cg[p], cb[p], cr[q], cg[q], cb[q]) > STEP) continue
                    seen[q] = true
                    queue[tail++] = q
                }
            }
            val count = tail
            if (count < 3) continue
            val cy = sy / count
            if (rowR[cy] < 0f) continue
            val far = dist(sr / count, sg / count, sb / count, rowR[cy], rowG[cy], rowB[cy])
            if (ss / count > ISLAND_SCORE && far < ISLAND_DIST) {
                for (i in 0 until count) sky[queue[i]] = 1f
            }
        }
    }

    /**
     * How much ([r], [g], [b]) looks like sky: clear blue (also deep dusk blue), overcast white and
     * grey (cool or neutral: a warm white is more often a wall), sunrise and sunset hues.
     */
    private fun skyColour(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val v = mx
        val sat = if (mx > 1e-4f) (mx - mn) / mx else 0f
        val hue = hue(r, g, b, mx, mn)
        val blue = band(hue, 175f, 195f, 245f, 268f) * smoothstep(0.05f, 0.14f, sat) *
            smoothstep(0.16f, 0.32f, v)
        val white = (1f - smoothstep(0.10f, 0.22f, sat)) * smoothstep(0.46f, 0.66f, v) *
            smoothstep(-0.12f, -0.02f, b - r)
        val warmHue = max(band(hue, -1f, 0f, 42f, 58f), band(hue, 272f, 292f, 360f, 361f))
        val warm = 0.85f * warmHue * smoothstep(0.12f, 0.3f, sat) * smoothstep(0.26f, 0.45f, v)
        // IchthysOS: dark overcast and storm cloud: low saturation, dim, cool or neutral.
        val storm = 0.7f * (1f - smoothstep(0.14f, 0.26f, sat)) * smoothstep(0.14f, 0.26f, v) *
            (1f - smoothstep(0.46f, 0.6f, v)) * smoothstep(-0.06f, 0.0f, b - r)
        // Sunset cloud undersides: dim, warm or violet, a little saturated.
        val dusk = 0.6f * warmHue * smoothstep(0.08f, 0.2f, sat) * smoothstep(0.12f, 0.24f, v) *
            (1f - smoothstep(0.3f, 0.45f, v))
        return max(max(blue, max(white, warm)), max(storm, dusk))
    }

    // ---- 3: the guided filter ------------------------------------------------------------------

    /**
     * The colour guided filter's coefficients (a_r, a_g, a_b, b, box-filtered) of [p] guided by
     * [px] (w x h); the matte anywhere is a · colour + b.
     */
    private fun guided(px: IntArray, w: Int, h: Int, p: FloatArray): Array<FloatArray> {
        val n = w * h
        val r = max(2, (max(w, h) / GUIDE_RADIUS_DIV.toFloat()).roundToInt())
        val ir = FloatArray(n)
        val ig = FloatArray(n)
        val ib = FloatArray(n)
        for (i in 0 until n) {
            ir[i] = Color.red(px[i]) / 255f
            ig[i] = Color.green(px[i]) / 255f
            ib[i] = Color.blue(px[i]) / 255f
        }
        val tmp = FloatArray(n)
        val prod = FloatArray(n)
        fun mean(src: FloatArray): FloatArray = FloatArray(n).also { box(src, w, h, r, tmp, it) }
        fun meanOf(a: FloatArray, b: FloatArray): FloatArray {
            for (i in 0 until n) prod[i] = a[i] * b[i]
            return mean(prod)
        }
        val mr = mean(ir)
        val mg = mean(ig)
        val mb = mean(ib)
        val mp = mean(p)
        val crp = meanOf(ir, p)
        val cgp = meanOf(ig, p)
        val cbp = meanOf(ib, p)
        val vrr = meanOf(ir, ir)
        val vrg = meanOf(ir, ig)
        val vrb = meanOf(ir, ib)
        val vgg = meanOf(ig, ig)
        val vgb = meanOf(ig, ib)
        val vbb = meanOf(ib, ib)
        // a and b per window, written over the means they came from.
        for (i in 0 until n) {
            val rr = vrr[i] - mr[i] * mr[i] + EPS
            val rg = vrg[i] - mr[i] * mg[i]
            val rb = vrb[i] - mr[i] * mb[i]
            val gg = vgg[i] - mg[i] * mg[i] + EPS
            val gb = vgb[i] - mg[i] * mb[i]
            val bb = vbb[i] - mb[i] * mb[i] + EPS
            val cr = crp[i] - mr[i] * mp[i]
            val cg = cgp[i] - mg[i] * mp[i]
            val cb = cbp[i] - mb[i] * mp[i]
            val i00 = gg * bb - gb * gb
            val i01 = gb * rb - rg * bb
            val i02 = rg * gb - gg * rb
            val i11 = rr * bb - rb * rb
            val i12 = rb * rg - rr * gb
            val i22 = rr * gg - rg * rg
            val det = rr * i00 + rg * i01 + rb * i02
            if (abs(det) < 1e-12f) {
                crp[i] = 0f
                cgp[i] = 0f
                cbp[i] = 0f
                vrr[i] = mp[i]
                continue
            }
            val ar = (i00 * cr + i01 * cg + i02 * cb) / det
            val ag = (i01 * cr + i11 * cg + i12 * cb) / det
            val ab = (i02 * cr + i12 * cg + i22 * cb) / det
            crp[i] = ar
            cgp[i] = ag
            cbp[i] = ab
            vrr[i] = mp[i] - ar * mr[i] - ag * mg[i] - ab * mb[i]
        }
        return arrayOf(mean(crp), mean(cgp), mean(cbp), mean(vrr))
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Fine texture: the luma gradient at the guide size (where foliage still has its detail),
     * averaged into the analysis grid.
     */
    private fun texture(px: IntArray, w: Int, h: Int, aw: Int, ah: Int): FloatArray {
        val luma = FloatArray(w * h)
        for (i in luma.indices) {
            val c = px[i]
            luma[i] = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val sum = FloatArray(aw * ah)
        val cnt = IntArray(aw * ah)
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val i = y * w + x
            val gr = abs(luma[i + 1] - luma[i]) + abs(luma[i + w] - luma[i])
            val a = min(ah - 1, y * ah / h) * aw + min(aw - 1, x * aw / w)
            sum[a] += gr
            cnt[a]++
        }
        for (i in sum.indices) if (cnt[i] > 0) sum[i] /= cnt[i]
        return sum
    }

    private fun alphaAt(mask: Bitmap, w: Int, h: Int): FloatArray {
        val small = scaleDown(mask.toSoftware(), w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== mask) small.recycle()
        return FloatArray(w * h) { (px[it] ushr 24) / 255f }
    }

    private fun dilate(src: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        morph(src, w, h, r, true)

    private fun erode(src: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        morph(src, w, h, r, false)

    /** Square max (or min) filter of radius [r], separable. */
    private fun morph(src: FloatArray, w: Int, h: Int, r: Int, grow: Boolean): FloatArray {
        val mid = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = if (grow) 0f else 1f
            for (k in max(0, x - r)..min(w - 1, x + r)) {
                val s = src[y * w + k]
                v = if (grow) max(v, s) else min(v, s)
            }
            mid[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = if (grow) 0f else 1f
            for (k in max(0, y - r)..min(h - 1, y + r)) {
                val s = mid[k * w + x]
                v = if (grow) max(v, s) else min(v, s)
            }
            out[y * w + x] = v
        }
        return out
    }

    /** Box mean of radius [r] (window clipped at the edges), separable running sums. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray, out: FloatArray) {
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (x in 0..min(w - 1, r)) sum += src[o + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w - 1, x + r)
                tmp[o + x] = sum / (hi - lo + 1)
                val add = x + r + 1
                val drop = x - r
                if (add < w) sum += src[o + add]
                if (drop >= 0) sum -= src[o + drop]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in 0..min(h - 1, r)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h - 1, y + r)
                out[y * w + x] = sum / (hi - lo + 1)
                val add = y + r + 1
                val drop = y - r
                if (add < h) sum += tmp[add * w + x]
                if (drop >= 0) sum -= tmp[drop * w + x]
            }
        }
    }

    private fun upsample(src: FloatArray, w: Int, h: Int, ow: Int, oh: Int): FloatArray {
        val out = FloatArray(ow * oh)
        val sx = w.toFloat() / ow
        val sy = h.toFloat() / oh
        for (y in 0 until oh) {
            val fy = (y + 0.5f) * sy - 0.5f
            for (x in 0 until ow) out[y * ow + x] = sample(src, w, h, (x + 0.5f) * sx - 0.5f, fy)
        }
        return out
    }

    /** Bilinear sample of [a] (w x h) at pixel coordinates ([x], [y]), clamped. */
    private fun sample(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val tx = cx - x0
        val ty = cy - y0
        val top = a[y0 * w + x0] + (a[y0 * w + x1] - a[y0 * w + x0]) * tx
        val bottom = a[y1 * w + x0] + (a[y1 * w + x1] - a[y1 * w + x0]) * tx
        return top + (bottom - top) * ty
    }

    /** [src] at [w] x [h], halving first so a big picture is averaged, not sampled. */
    private fun scaleDown(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= w * 2 && cur.height / 2 >= h * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == w && cur.height == h) return cur
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    private fun Bitmap.toSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    private fun dist(r0: Float, g0: Float, b0: Float, r1: Float, g1: Float, b1: Float): Float {
        val dr = r0 - r1
        val dg = g0 - g1
        val db = b0 - b1
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d < 1e-4f) return -1000f
        val h =
            when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
        return if (h < 0f) h + 360f else h
    }

    /** 0 outside [a]..[d], 1 inside [b]..[c], smooth between. */
    private fun band(v: Float, a: Float, b: Float, c: Float, d: Float): Float =
        smoothstep(a, b, v) * (1f - smoothstep(c, d, v))

    private fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val OUT_SIDE = 1440
        const val GUIDE_SIDE = 720
        const val ANALYSIS_SIDE = 288

        /** Fine texture (mean luma gradient per guide pixel) below LOW is smooth, above HIGH not. */
        const val TEX_LOW = 0.012f
        const val TEX_HIGH = 0.04f

        /** Scores that seed the sky in the top rows, let it grow, and grow across bigger steps. */
        const val SEED = 0.4f
        const val GROW = 0.22f
        const val STRONG = 0.55f
        /** Colour change allowed from one analysis pixel to the next (RGB distance, 0..1.7). */
        const val STEP = 0.07f
        const val STEP_STRONG = 0.16f
        /** ... and away from the carried reference colour, which follows at REF_RATE per pixel. */
        const val DRIFT = 0.2f
        const val REF_RATE = 0.12f

        /** Holes up to this share of the picture are filled (see fillHoles). */
        const val HOLE_SHARE = 0.06f
        const val ISLAND_SCORE = 0.42f
        const val ISLAND_DIST = 0.16f

        /** Analysis pixels the matte may reach beyond the coarse sky, and is surely sky within. */
        const val GATE_PX = 2
        const val FLOOR_PX = 2

        /** Guided filter: window radius = the guide's long side / this; regularisation. */
        const val GUIDE_RADIUS_DIV = 90
        const val EPS = 4e-4f

        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}
