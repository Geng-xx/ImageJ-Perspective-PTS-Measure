# ImageJ Perspective PTS Measure

Perspective-correct scale setting and length measurement in **ImageJ 1.x** using 4-point `.pts` sidecar files.

This toolkit is designed for research workflows where images are captured with perspective distortion. You provide four corner points of a planar region (exported from ImageJ ROI into a `.pts` file). Measurements drawn on the original image are then mapped into a rectified plane via a homography, enabling consistent scale and length measurement.

## Features

- Export 4 corner points from an ImageJ selection to a `.pts` sidecar file.
- Set a perspective-correct scale from a ruler line (known distance + unit) using the `.pts` model.
- Measure line or polyline length on the original image, computed in the rectified plane.
- Batch perspective rectification for a folder of images using per-image `.pts` files.
- Batch pipeline (rectify + optional auto ruler-scale + segmentation + skeleton length + CSV).

## Requirements

- ImageJ 1.x (tested on **ImageJ 1.54g**) and Java 8.
- For each image you want to process, a matching `.pts` file containing 4 points.

## Repository Layout

This repo mirrors the ImageJ installation folder layout. Copy these folders into your ImageJ root directory:

- `plugins/`
- `plugins/Scripts/`
- `macros/`

Key files:

- `plugins/Scripts/Export_Rectangle_Corners_To_PTS.ijm`
- `plugins/Perspective_Set_Scale_With_PTS.java`
- `plugins/Perspective_Measure_With_PTS.java`
- `plugins/Perspective_Correct_Batch.java`
- `plugins/Rectify_Scale_Skeleton_Measure_Batch.java`
- `macros/StartupMacros.txt`
- `macros/PerspectiveCorrectBatch.ijm`
- `macros/RectifyScaleSkeletonMeasureBatch.ijm`

## Installation

1. Copy the `plugins/` and `macros/` folders from this repo into your ImageJ folder.
1. Restart ImageJ.
1. Compile the Java plugins if needed:

   - In ImageJ: `Plugins > Compile and Run...` and select the `.java` file under `plugins/`.

## Shortcuts

ImageJ macro shortcuts support single keys and function keys, but not reliable `Ctrl/Alt` combinations. This project uses function keys (configured in `macros/StartupMacros.txt`):

- `F9`: Export Rectangle Corners To PTS (Selection)
- `F10`: Perspective Set Scale With PTS
- `F11`: Perspective Measure With PTS

Shortcuts take effect after restarting ImageJ.

## Quick Start (Interactive Measurement)

1. Open an image.
1. Create a 4-point selection around the planar region of interest:

   - Rectangle selection, or
   - 4-point polygon selection

1. Run `Export Rectangle Corners To PTS` to write a `.pts` file next to the image.
1. Draw a straight line on the ruler (known distance).
1. Run `Perspective Set Scale With PTS`, enter the known distance and unit.
1. Draw a line or polyline on the structure you want to measure.
1. Run `Perspective Measure With PTS`.

What gets stored:

- The scale is stored on the image as properties (`perspPts.unit`, `perspPts.unitPerRectPx`).
- Optionally, the same scale can be saved as a global default in ImageJ preferences.

## Batch Processing

### 1) Batch Rectify Images (Folder In, Rectified Images Out)

- Plugin: `Perspective_Correct_Batch`
- Macro entry: `macros/PerspectiveCorrectBatch.ijm`

Input:

- A folder of images.
- For each image, a `.pts` file (same base name).

Output:

- Rectified images written to an output folder.

### 2) End-to-End Batch Pipeline

- Plugin: `Rectify_Scale_Skeleton_Measure_Batch`
- Macro entry: `macros/RectifyScaleSkeletonMeasureBatch.ijm`

Pipeline steps:

- Perspective rectify using `.pts`.
- Estimate scale from the top ruler band (may fail depending on ruler quality).
- Segment structures, skeletonize, measure skeleton length per connected component.
- Write a CSV summary to the output folder.

## `.pts` Sidecar Format

- Plain text with exactly **4 points**.
- Each line is `x y` or `x,y`.
- Points are in source image pixel coordinates.
- Point order can be arbitrary; plugins reorder to `TL, TR, BR, BL`.

File naming rules (next to the image):

- Preferred: `<imageBaseName>.pts` (e.g. `Y_24.pts` for `Y_24.jpg`)
- Also accepted: `<imageFileName>.pts` (e.g. `Y_24.jpg.pts`)

## Notes and Limitations

- The batch auto ruler detection can fail if the ruler is blurry, low-contrast, partially occluded, or not near the top of the rectified image.
- For highest accuracy and reproducibility, prefer the interactive workflow:

  - export `.pts`
  - set scale from a manually drawn ruler line
  - measure with `Perspective Measure With PTS`

## Citation

If you use this toolkit in a paper, consider citing it as software:

- *ImageJ Perspective PTS Measure*, GitHub repository, accessed YYYY-MM-DD.
