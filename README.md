# MemeGenFR: Real-time Expression & Gesture Recognition

MemeGenFR is an Android application built with Jetpack Compose that utilizes a multi-layered Computer Vision pipeline to detect human expressions and physical gestures. By combining facial contours, skeletal landmarks, and hand geometry, the app triggers contextual overlays to create real-time interactive "memes."

---

## 🧬 Core Recognition Techniques

The project focuses on two primary detection pillars: **Adaptive Facial Expression Analysis** and **Geometric Hand/Wrist Tracking**.

### 1. Facial Expression Recognition
Unlike static classification models, this project uses an **Adaptive Baseline System** to account for different face shapes and resting states.

* **Calibration Phase**: Upon startup, the `FaceBaseline` object collects ~60 samples of the user's neutral face to calculate median "resting" values for mouth and brow positions.
* **Expression Logic**:
    * **Surprise**: Triggered when the distance between `LOWER_LIP_BOTTOM` and `UPPER_LIP_TOP` exceeds the neutral baseline by a set threshold.
    * **Anger/Sadness**: Detected by analyzing the `browFurrow`, which measures the Y-axis delta between inner and outer eyebrow points.
    * **Head Orientation**: Uses Euler angles (Yaw, Pitch, Roll) to detect "looking_left," "looking_up," or "head_tilt".



### 2. Hand & Wrist Recognition
The project utilizes the MediaPipe Hand Landmarker to track 21 3D landmarks, focusing on the relationship between the wrist and digit tips.

* **Wrist Anchoring**: The wrist (Landmark 0) serves as the origin point for calculating extension ratios.
* **Finger Extension Detection**: A finger is classified as "extended" if the distance from the wrist to the tip is greater than the distance from the wrist to the PIP (middle joint).
* **Complex Gesture Logic**:
    * **Pointing**: Calculated by the vector between `INDEX_MCP` and `INDEX_TIP`, corrected for front-camera mirroring.
    * **Peace Sign**: Detects exactly two extended fingers (Index and Middle).
    * **Double Gun**: A compound check across both hands requiring the Index and Thumb to be extended while others are folded, combined with a vertical Y-axis check.

---

## ⚙️ Project Pipeline

The application processes frames through a hybrid serial-parallel pipeline to maintain real-time performance.

1.  **Image Acquisition**: CameraX captures `ImageProxy` frames, which are converted to upright Bitmaps for processing.
2.  **Parallel Inference**:
    * **ML Kit Pose**: Detects skeletal points (shoulders, elbows, wrists) to find "hands_up" or "leaning" states.
    * **ML Kit Face**: Extracts detailed contours and smiling probabilities.
    * **MediaPipe Hands**: Performs high-fidelity landmarking for 2D/3D hand coordinates.
3.  **Keyword Extraction**: Specific singleton objects (`FaceKeywordExtractor`, `PoseKeywordExtractor`, `HandKeywordExtractor`) translate raw coordinates into semantic strings like `fist`, `smiling`, or `left_hand_raised`.
4.  **Keyword Resolution**: The `OverlayAssetResolver` checks for matches in the `COMPOUND_CONDITIONS` (e.g., `left_hand_raised` + `looking_left`) or single keywords to display the appropriate drawable.
5.  **Output**: A `DebugLandmarkView` renders the skeleton and contours over the camera preview, while the UI provides a mechanism to save the final result to the gallery.

---

## 🛠️ Technical Stack
* **UI**: Jetpack Compose (Material 3)
* **Camera**: Android CameraX
* **ML Engines**: Google ML Kit (Face & Pose) + Google MediaPipe (Hand Landmarker)
* **Language**: Kotlin
