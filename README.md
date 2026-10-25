# IchthysDepth

The lock screen's depth cut-out (kusti420/ichthysos-tracker#58). SystemUI draws the lock
wallpaper's subject over the clock; this app finds the subject.

- `MaskProvider` serves `content://org.ichthys.depth/mask/<userId>/<key>`, a PNG mask (white, subject
  in alpha) of that user's current lock wallpaper, `<key>` being `L<lock id>S<home id>`. A missing
  mask is made in the background (about 4 s on the A52s) and `content://org.ichthys.depth/mask/<userId>`
  is notified when it is there. Readers need READ_WALLPAPER_INTERNAL, which SystemUI holds.
- `Segmenter` runs U²-Netp (salient object detection) on TFLite at 320x320, scales the result to
  the picture (at most 1440 px) and sharpens its edge.
- Masks are kept in device-protected storage, so the lock screen has them before the first unlock.

## Model

`assets/u2netp.tflite`: U²-Netp from https://github.com/xuebinqin/U-2-Net (Apache License 2.0),
Xuebin Qin et al., "U²-Net: Going Deeper with Nested U-Structure for Salient Object Detection",
Pattern Recognition 2020. Converted from rembg's `u2netp.onnx` (sha256 309c8469…ddd8) with
onnxsim 0.4.36 and onnx2tf 1.26 (float32, NHWC input 1x320x320x3, ImageNet normalisation);
it matches the ONNX output to 1e-4. sha256 of the TFLite file:
7bb1284a6c776d975ad58de8bd8e7a6995ddcfec7ed50ed98147dcf91a142321.

## Trying it

    adb root
    adb shell content read --uri content://org.ichthys.depth/mask/0/x   # starts a mask
    adb logcat -s IchthysDepth
    adb shell settings put secure ichthys_lockscreen_depth 1
