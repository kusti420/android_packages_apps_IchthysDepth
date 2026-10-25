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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt
import org.tensorflow.lite.Interpreter

/**
 * Finds the subject of a picture: U²-Netp (salient object detection; Qin et al., "U²-Net: Going
 * Deeper with Nested U-Structure for Salient Object Detection", Apache-2.0), converted to a float32
 * TFLite model (input 1×320×320×3 NHWC, ImageNet-normalised RGB; output 1×320×320×1, 0..1).
 *
 * The result is a mask: white, with the subject's coverage in the alpha channel, the picture's
 * aspect ratio and at most [MAX_MASK_SIDE] pixels on its long side (SystemUI scales it to the
 * picture). The model's soft output is sharpened by [EDGE_LOW]..[EDGE_HIGH], so the background
 * doesn't show as a haze around the subject.
 */
class Segmenter(private val context: Context) {

    fun mask(picture: Bitmap): Bitmap {
        val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        val small = Bitmap.createScaledBitmap(picture.toSoftware(), SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== picture) small.recycle()
        val fb = input.asFloatBuffer()
        for (p in px) {
            fb.put((Color.red(p) / 255f - MEAN[0]) / STD[0])
            fb.put((Color.green(p) / 255f - MEAN[1]) / STD[1])
            fb.put((Color.blue(p) / 255f - MEAN[2]) / STD[2])
        }
        val output = ByteBuffer.allocateDirect(4 * SIZE * SIZE).order(ByteOrder.nativeOrder())
        Interpreter(model(), Interpreter.Options().setNumThreads(THREADS)).use { it.run(input, output) }
        output.rewind()
        val out = FloatArray(SIZE * SIZE)
        output.asFloatBuffer().get(out)

        // The raw map, stretched to 0..1, as grey; scaled up to the mask size with filtering, then
        // sharpened there, so the edge follows the picture smoothly instead of in 320 steps.
        val lo = out.min()
        val span = max(out.max() - lo, 1e-6f)
        for (i in px.indices) {
            val v = ((out[i] - lo) / span * 255f).roundToInt().coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }
        val grey = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(1f, MAX_MASK_SIDE.toFloat() / max(picture.width, picture.height))
        val w = max(1, (picture.width * scale).roundToInt())
        val h = max(1, (picture.height * scale).roundToInt())
        val big = Bitmap.createScaledBitmap(grey, w, h, true)
        grey.recycle()
        val row = IntArray(w)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            big.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val a = (smoothstep(Color.red(row[x]) / 255f) * 255f).roundToInt()
                row[x] = Color.argb(a, 255, 255, 255)
            }
            mask.setPixels(row, 0, w, 0, y, w, 1)
        }
        big.recycle()
        return mask
    }

    private fun smoothstep(v: Float): Float {
        val t = ((v - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The model, memory-mapped out of the APK (stored uncompressed, see Android.bp). */
    private fun model(): ByteBuffer =
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    private fun Bitmap.toSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    private companion object {
        const val MODEL = "u2netp.tflite"
        const val SIZE = 320
        const val THREADS = 4
        const val MAX_MASK_SIDE = 1440
        const val EDGE_LOW = 0.25f
        const val EDGE_HIGH = 0.75f
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
