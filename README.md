# MemeGenFR (Face + Pose + Hands → Meme Overlay)

MemeGenFR is an Android camera app that detects **face expression**, **body pose**, and **hand gestures** in real time, converts detections into **semantic keywords**, then resolves **one final overlay** (meme image) to display and optionally save.

It also supports a **Teach + Library** workflow: teach a gesture using an image, then match it live using similarity scoring.

---

## 1) Quick Demo

1) Launch the app → allow **Camera permission**  
2) Hold a **neutral face** at the start to let calibration run  
3) Perform gestures:
- **Peace sign** → keyword overlay (e.g., `peace_sign`)
- **Both hands up** → keyword overlay (e.g., `both_hands_up`)
4) Toggle **Debug ON** → landmarks/skeleton are drawn to verify detection  
5) Tap **Teach** → select an image with a clear gesture → save to Library  
6) Perform that gesture live → if match score ≥ **0.80**, overlay shows **Learned match**  
7) Tap **Save Image** → saves output to **Pictures/MemeGenFR**

---

## 2) Core Pipeline (How the program works)

### A) CameraX Frame Pipeline
- Uses **CameraX Preview + ImageAnalysis**
- Uses `STRATEGY_KEEP_ONLY_LATEST` so analysis doesn’t lag behind the preview
- Frames are processed off the UI thread for responsiveness

### B) Detection Pipeline (Per Frame)
Detectors run each frame (or as available):
- **Face detection** (ML Kit) → face bounds / features used for expression keywords
- **Pose detection** (ML Kit) → body landmarks used for pose keywords
- **Hand landmarks** (MediaPipe) → 21 landmarks per hand used for hand keywords

### C) Keyword Extraction
Detector outputs are converted into **keywords** such as:
- Face: `smiling`, `surprised`, `angry`, `neutral`, `looking_left`, etc.
- Pose: `hands_up`, `left_hand_raised`, `right_hand_raised`, etc.
- Hands: `peace_sign`, `thumbs_up`, `fist`, `ok_sign`, `double_gun`, etc.

Keywords are merged + deduplicated into a single set for the frame.

### D) Overlay Resolution (Pick 1 Final Overlay)
Priority rules:
1. **Learned match overlay** (if taught gesture matches live with score ≥ 0.80)
2. **Built-in keyword overlay** (keyword → drawable mapping)
3. Fallback state (e.g., “no detection” / no overlay)

---

## 3) Teach + Library (Custom Gestures)

### Teach (from a static image)
- User selects an image containing a gesture
- App extracts a **Gesture Feature Vector** from the image
- User labels it and saves it into the **gesture library**

### Match (live)
- Each camera frame builds a **Live Feature Vector**
- A matcher computes similarity between live vector and stored vectors
- If best score ≥ **0.80**, the app displays a **Learned match** overlay

### Storage
- Saved gestures persist across launches (stored in app internal storage)
- Library UI allows viewing and deleting taught gestures

---

## 4) Calibration (Neutral Baseline)

At the start, the app collects a neutral baseline for face-related features (e.g., mouth/brow metrics).
Until calibration finishes:
- A calibration indicator is shown (e.g., `Calibrating… x/60`)
- Some face-related keywords may be suppressed or treated as “baseline”

If calibration seems slow:
- Ensure face is clearly visible, good lighting, minimal head rotation
- Turn **Debug ON** to confirm face landmarks/features are being detected
- Use the **Recalibrate** control if provided

---

## 5) Debug Layer (Landmark Overlay)

When Debug is enabled:
- Face bounds / features are drawn
- Pose skeleton is drawn
- Hand landmarks and connections are drawn

This is primarily for verification:
- confirms coordinate mapping (mirroring/rotation)
- confirms detectors are running correctly

---

## 6) Save Image Behavior

- “Save Image” exports the current overlay result into the device gallery
- Saved location: **Pictures/MemeGenFR**
- A toast/snackbar confirms success (if enabled in your UI)

---

## 7) Reliability Behavior / Edge Cases

- If no face/hands are detected:
  - the app falls back to a “no detection” overlay/state
- If detectors are slow:
  - `KEEP_ONLY_LATEST` ensures UI remains responsive
- If learned gestures do not match:
  - the app continues to use built-in keyword overlays

---

## 8) Permissions / Requirements

### Required
- `android.permission.CAMERA`

### Model / Assets
- MediaPipe HandLandmarker requires the `.task` model file placed in app assets (if used in your implementation)

---

## 9) Suggested Test Cases (What we verified)

| Test | Input | Expected Output |
|------|-------|-----------------|
| Calibration start | Neutral face at launch | Shows `Calibrating…` until baseline completes |
| Hands up pose | Both wrists above shoulders | Keyword includes `hands_up` |
| Two-hand open | Two open hands visible | Keyword includes `both_hands_up` (or equivalent) |
| Peace sign | Index + middle extended | Keyword includes `peace_sign` |
| Double gun | Both hands gun pose | Keyword includes `double_gun` |
| No detection | No face/hands in view | Fallback keyword/state appears |
| Teach + learned match | Teach gesture image, then perform live | Match score ≥ 0.80 and overlay shows “Learned match” |
| Save image | Press “Save Image” while overlay shown | Image saved to Pictures/MemeGenFR and confirmation shown |

---

## 10) Project Structure (Where to look)

Typical key files (names may differ based on your project):
- `MainActivity.kt` — entry point
- `CameraPreview.kt` — CameraX preview + analysis loop
- `PoseExpressionApp.kt` — main UI + resolver + teach/library controls
- `KeywordExtractors.kt` — face/pose/hand → keywords
- `GestureFeatureVector.kt` — feature vector model
- `LiveFeatureBuilder.kt` — build live features from detections
- `StaticImageExtractor.kt` — build features from a chosen image
- `GestureMatcher.kt` — similarity scoring + threshold
- `GestureStore.kt` — persistence for taught gestures
- `OverlayAssetResolver.kt` — keyword → drawable mapping
- `DebugLandmarkView.kt` — landmark drawing overlay
- `SaveUtils.kt` — saving overlay output to gallery

---

## 11) Build / Run

1) Open the project in **Android Studio**
2) Sync Gradle
3) Run on a physical Android device (recommended) or emulator with camera support
4) Grant camera permission on first launch

---

## Credits / Libraries
- CameraX (camera preview + analysis)
- ML Kit (face + pose detection)
- MediaPipe (hand landmarks)