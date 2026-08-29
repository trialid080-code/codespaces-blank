# AstroPad Camera — Xiaomi Pad 7

Android Camera2 astrophotography camera prototype targeting Xiaomi Pad 7 (2410CRP4CI).

## Current
- Rear-camera selection
- Camera2 manual ISO
- Manual shutter/exposure time
- Manual lens focus distance
- RAW_SENSOR ImageReader
- DNG/RAW capture pipeline foundation
- Multi-frame capture UI
- Native C++ stacking API placeholder

## Next milestones
1. Replace raw byte dump with proper Android `DngCreator` output.
2. Add runtime sensor ranges and safe clamping from CameraCharacteristics.
3. Add LibRaw RAW decoder through NDK.
4. Port/adapt star detection, registration, and stacking concepts from the supplied Siril 1.4.4 source.
5. Add dark/flat calibration, sigma clipping, sub-pixel registration, debayering, histogram/stretch and 16-bit TIFF/FITS export.
6. Add live histogram, focus assist, exposure warnings, session browser and resume.

The Xiaomi report indicates RAW, MANUAL_SENSOR, BURST_CAPTURE, MANUAL_POST_PROCESSING, manual exposure/focus and AE/AWB lock capabilities, which this architecture is designed around.
