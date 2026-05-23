import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * End-to-end batch:
 * 1) Perspective rectify using sidecar 4-point .pts
 * 2) Auto scale from top ruler area using large tick spacing (1 cm)
 * 3) Segment structures, skeletonize, measure skeleton length per connected component
 * 4) Write per-image results CSV
 */
public class Rectify_Scale_Skeleton_Measure_Batch implements PlugIn {

    private static final String PTS_EXT = ".pts";
    private static final String[] IMAGE_EXTS = new String[]{"tif", "tiff", "png", "jpg", "jpeg", "bmp", "gif"};

    public void run(String arg) {
        Locale.setDefault(Locale.US);

        Args a;
        try {
            a = Args.parse(arg);
        } catch (IllegalArgumentException e) {
            IJ.error("Rectify+Scale+Skeleton (Batch)", e.getMessage());
            return;
        }

        File inDir = new File(a.inputDir);
        File outDir = new File(a.outputDir);
        if (!inDir.isDirectory()) {
            IJ.error("Rectify+Scale+Skeleton (Batch)", "Input is not a directory: " + a.inputDir);
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            IJ.error("Rectify+Scale+Skeleton (Batch)", "Failed to create output directory: " + a.outputDir);
            return;
        }

        Map<String, File> ptsByKey = indexPtsFiles(inDir);
        List<File> images = listImages(inDir);
        if (images.isEmpty()) {
            IJ.log("No images found under: " + inDir.getAbsolutePath());
            return;
        }

        ResultsTable all = new ResultsTable();
        int row = 0;

        for (int i = 0; i < images.size(); i++) {
            File imgFile = images.get(i);
            File ptsFile = findPtsForImage(ptsByKey, imgFile);
            if (ptsFile == null) {
                IJ.log("SKIP (missing .pts): " + imgFile.getName());
                continue;
            }

            IJ.showStatus("Processing " + (i + 1) + "/" + images.size() + ": " + imgFile.getName());
            IJ.showProgress(i + 1, images.size());

            ImagePlus imp = IJ.openImage(imgFile.getAbsolutePath());
            if (imp == null) {
                IJ.log("SKIP (open failed): " + imgFile.getName());
                continue;
            }

            try {
                Point2D.Double[] src = orderCornersTLTRBRBL(read4Points(ptsFile));
                int outW = estimateWidth(src);
                int outH = estimateHeight(src);
                if (outW < 1 || outH < 1) throw new IllegalArgumentException("Bad output size");

                Point2D.Double[] dst = new Point2D.Double[]{
                        new Point2D.Double(0, 0),
                        new Point2D.Double(outW - 1, 0),
                        new Point2D.Double(outW - 1, outH - 1),
                        new Point2D.Double(0, outH - 1)
                };
                double[] H = computeHomography(dst, src); // dst->src

                ImageProcessor rect = Perspective.warp(imp.getProcessor(), outW, outH, H, a.interpolation);
                ImagePlus rectImp = new ImagePlus(baseName(imgFile.getName()) + a.rectSuffix, rect);

                // Auto scale from top ruler band
                String bname = baseName(imgFile.getName());
                double pixelsPerCm = estimatePixelsPerCm(
                        rectImp,
                        a.rulerBandFrac,
                        a.rulerMinTickFrac,
                        a.rulerMaxTickFrac,
                        a.debugSaveRulerCrop,
                        outDir,
                        bname
                );
                if (pixelsPerCm <= 0) throw new IllegalArgumentException("Failed to estimate ruler scale");
                IJ.log("Scale(" + imgFile.getName() + "): pixelsPerCm=" + pixelsPerCm);
                // unit per pixel (assumes unit is mm)
                double unitPerPixel;
                if (a.unit.trim().equalsIgnoreCase("mm")) {
                    unitPerPixel = 10.0 / pixelsPerCm;
                } else if (a.unit.trim().equalsIgnoreCase("cm")) {
                    unitPerPixel = 1.0 / pixelsPerCm;
                } else {
                    // default to mm semantics
                    unitPerPixel = 10.0 / pixelsPerCm;
                }
                rectImp.getCalibration().setUnit(a.unit);
                rectImp.getCalibration().pixelWidth = unitPerPixel;
                rectImp.getCalibration().pixelHeight = unitPerPixel;

                // Segment structures and measure skeleton lengths
                List<ComponentMeasure> ms = measureSkeletonLengths(rectImp, a);
                for (ComponentMeasure m : ms) {
                    all.incrementCounter();
                    all.addValue("file", imgFile.getName());
                    all.addValue("id", m.id);
                    all.addValue("skeleton_length_" + a.unit, m.length);
                    all.addValue("area_px", m.areaPx);
                    row++;
                }

                // Save rectified image optionally
                if (a.saveRectified) {
                    File outImg = new File(outDir, bname + a.rectSuffix + "." + extension(imgFile.getName()));
                    Perspective.save(new ImagePlus(rectImp.getTitle(), rectImp.getProcessor()), outImg);
                }

                rectImp.close();
            } catch (Exception e) {
                IJ.log("SKIP (error): " + imgFile.getName() + " -> " + e.getMessage());
            } finally {
                imp.close();
            }
        }

        // Write results
        File outCsv = new File(outDir, a.resultsName);
        try {
            all.saveAs(outCsv.getAbsolutePath());
            IJ.log("Wrote results: " + outCsv.getAbsolutePath());
        } catch (IOException e) {
            IJ.error("Rectify+Scale+Skeleton (Batch)", "Failed to save results: " + e.getMessage());
        }
    }

    private static class Args {
        String inputDir;
        String outputDir;
        String rectSuffix = "_rect";
        String resultsName = "measurements.csv";
        boolean saveRectified = true;
        String interpolation = "Bicubic";
        String unit = "mm";

        // ruler detection
        double rulerBandFrac = 0.25; // top 25% of rectified image
        double rulerMinTickFrac = 0.02; // ignore too-small spacings
        double rulerMaxTickFrac = 0.25; // ignore too-large spacings

        // segmentation
        int minAreaPx = 200;
        boolean invert = false;

        // debug
        boolean debugSaveRulerCrop = false;

        static Args parse(String arg) {
            Args a = new Args();
            if (arg == null) arg = "";
            arg = arg.trim();
            if (arg.isEmpty()) throw new IllegalArgumentException("Missing args. Example: input=C:\\in output=C:\\out");

            Map<String, String> kv = parseKv(arg);
            a.inputDir = req(kv, "input");
            a.outputDir = req(kv, "output");
            if (kv.containsKey("rectsuffix")) a.rectSuffix = kv.get("rectsuffix");
            if (kv.containsKey("results")) a.resultsName = kv.get("results");
            if (kv.containsKey("saverect")) a.saveRectified = bool(kv.get("saverect"));
            if (kv.containsKey("interp")) a.interpolation = Perspective.normalizeInterp(kv.get("interp"));
            if (kv.containsKey("unit")) a.unit = kv.get("unit");
            if (kv.containsKey("minareapx")) a.minAreaPx = Integer.parseInt(kv.get("minareapx"));
            if (kv.containsKey("invert")) a.invert = bool(kv.get("invert"));
            if (kv.containsKey("debugruler")) a.debugSaveRulerCrop = bool(kv.get("debugruler"));
            return a;
        }

        private static boolean bool(String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("y");
        }

        private static String req(Map<String, String> kv, String k) {
            String v = kv.get(k);
            if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing " + k + "=...");
            return v.trim();
        }

        private static Map<String, String> parseKv(String raw) {
            Map<String, String> out = new HashMap<String, String>();
            // keys are separated by spaces or commas. values may contain backslashes.
            String[] tokens = raw.split("[\\s,]+(?=\\w+=)");
            for (String t : tokens) {
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = t.substring(eq + 1).trim();
                out.put(k, v);
            }
            return out;
        }
    }

    // --- Ruler scale estimation (large ticks = 1 cm) ---

    private static double estimatePixelsPerCm(ImagePlus rectImp, double bandFrac, double minTickFrac, double maxTickFrac,
                                              boolean debugSaveCrop, File outDir, String baseName) {
        ImageProcessor ip = rectImp.getProcessor().duplicate();
        ip = ip.convertToByte(true);
        int w = ip.getWidth();
        int h = ip.getHeight();
        int bandH = Math.max(1, (int) Math.round(h * bandFrac));

        // ROI: top band, ignore margins
        Rectangle roi = new Rectangle((int) (w * 0.05), 0, (int) (w * 0.90), bandH);
        ip.setRoi(roi);
        ImageProcessor top = ip.crop();

        if (debugSaveCrop && outDir != null) {
            File f = new File(outDir, baseName + "_rulerCrop.png");
            Perspective.save(new ImagePlus(baseName + "_rulerCrop", top), f);
        }

        int tw = top.getWidth();
        int th = top.getHeight();
        // Focus on the lower part of the ruler band: major (1cm) ticks extend further.
        int y0 = (int) (th * 0.55);
        int y1 = th;

        // vertical projection: sum darkness per column (robust to overall exposure)
        double[] score = new double[tw];
        for (int x = 0; x < tw; x++) {
            double s = 0.0;
            for (int y = y0; y < y1; y++) {
                int v = top.get(x, y) & 0xff;
                s += (255 - v);
            }
            score[x] = s;
        }

        // light smoothing to reduce noise / minor ticks
        double[] smooth = new double[tw];
        int win = 5;
        int hw = win / 2;
        for (int x = 0; x < tw; x++) {
            double s = 0.0;
            int c = 0;
            for (int k = -hw; k <= hw; k++) {
                int xx = x + k;
                if (xx < 0 || xx >= tw) continue;
                s += score[xx];
                c++;
            }
            smooth[x] = s / Math.max(1, c);
        }

        // detect peaks by local maxima over a threshold
        double max = 0.0;
        for (double v : smooth) if (v > max) max = v;
        double thr = max * 0.75;
        List<Integer> peaks = new ArrayList<Integer>();
        for (int x = 1; x < tw - 1; x++) {
            if (smooth[x] >= thr && smooth[x] >= smooth[x - 1] && smooth[x] >= smooth[x + 1]) {
                // de-duplicate nearby peaks
                if (!peaks.isEmpty() && x - peaks.get(peaks.size() - 1) <= 3) continue;
                peaks.add(x);
            }
        }

        if (peaks.size() < 3) return -1;

        // compute spacings and take robust median in acceptable range
        List<Integer> dxs = new ArrayList<Integer>();
        int minDx = (int) Math.round(tw * minTickFrac);
        int maxDx = (int) Math.round(tw * maxTickFrac);
        for (int i = 0; i < peaks.size() - 1; i++) {
            int dx = peaks.get(i + 1) - peaks.get(i);
            if (dx >= minDx && dx <= maxDx) dxs.add(dx);
        }
        if (dxs.isEmpty()) return -1;
        dxs.sort(Integer::compareTo);
        int median = dxs.get(dxs.size() / 2);

        // We are using large ticks (1 cm). With the y-range restricted to the bottom of the band
        // and a high threshold, peaks should mostly be major ticks.
        return median;
    }

    // --- Skeleton length measurement ---

    private static class ComponentMeasure {
        int id;
        double length;
        int areaPx;
    }

    private static List<ComponentMeasure> measureSkeletonLengths(ImagePlus rectImp, Args a) {
        ImageProcessor ip = rectImp.getProcessor().duplicate();
        if (!(ip instanceof ij.process.ByteProcessor)) {
            ip = ip.convertToByte(true);
        }
        if (a.invert) ip.invert();

        // Segment: simple global threshold to binary mask
        int thr = otsuThreshold((ij.process.ByteProcessor) ip);
        ij.process.ByteProcessor mask = (ij.process.ByteProcessor) ip.createProcessor(ip.getWidth(), ip.getHeight());
        int w = ip.getWidth();
        int h = ip.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = ip.get(x, y) & 0xff;
                int fg = (v <= thr) ? 255 : 0; // assume dark foreground
                mask.set(x, y, fg);
            }
        }

        // Morphology: erode then dilate (opening) to remove small noise
        {
            ij.process.BinaryProcessor bp = new ij.process.BinaryProcessor(mask);
            bp.erode();
            bp.dilate();
            mask = (ij.process.ByteProcessor) bp;
        }

        // Label connected components on mask
        w = mask.getWidth();
        h = mask.getHeight();

        int[] labels = new int[w * h];
        int nextId = 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (labels[idx] != 0) continue;
                int v = mask.get(x, y) & 0xff;
                if (v == 0) continue; // background
                int area = floodFill4(mask, labels, w, h, x, y, nextId);
                if (area >= a.minAreaPx) {
                    nextId++;
                } else {
                    // clear small component
                    for (int k = 0; k < labels.length; k++) if (labels[k] == nextId) labels[k] = 0;
                }
            }
        }

        int maxId = nextId - 1;
        List<ComponentMeasure> out = new ArrayList<ComponentMeasure>();
        for (int id = 1; id <= maxId; id++) {
            // build component mask
            ImageProcessor comp = mask.createProcessor(w, h);
            int area = 0;
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == id) {
                    comp.set(i % w, i / w, 255);
                    area++;
                }
            }
            if (area < a.minAreaPx) continue;

            ImagePlus compImp = new ImagePlus("c" + id, comp);

            ij.process.ByteProcessor skel = skeletonizeForeground((ij.process.ByteProcessor) compImp.getProcessor().convertToByte(true));
            double len = skeletonLength8Connected(skel, rectImp.getCalibration().pixelWidth);
            // Heuristic: if the scale estimation fails silently, lengths can blow up.
            if (len > 1e6) {
                IJ.log("WARN large length id=" + id + " len=" + len + " (unit=" + rectImp.getCalibration().getUnit() + ")");
            }

            ComponentMeasure m = new ComponentMeasure();
            m.id = id;
            m.length = len;
            m.areaPx = area;
            out.add(m);
            compImp.close();
        }
        return out;
    }

    /** Otsu threshold for 8-bit image. Returns threshold in [0,255]. */
    private static int otsuThreshold(ij.process.ByteProcessor ip) {
        int[] hist = ip.getHistogram();
        int total = ip.getWidth() * ip.getHeight();
        double sum = 0.0;
        for (int t = 0; t < 256; t++) sum += t * (double) hist[t];

        double sumB = 0.0;
        int wB = 0;
        int wF;
        double maxVar = -1.0;
        int threshold = 128;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            wF = total - wB;
            if (wF == 0) break;
            sumB += t * (double) hist[t];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double varBetween = (double) wB * (double) wF * (mB - mF) * (mB - mF);
            if (varBetween > maxVar) {
                maxVar = varBetween;
                threshold = t;
            }
        }
        return threshold;
    }

    private static double skeletonLength8Connected(ImageProcessor skel, double unitPerPixel) {
        int w = skel.getWidth();
        int h = skel.getHeight();
        double lengthPx = 0.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = skel.get(x, y) & 0xff;
                if (v == 0) continue;
                // count edges to right/down/down-right/down-left to avoid double counting
                if (x + 1 < w && (skel.get(x + 1, y) & 0xff) != 0) lengthPx += 1.0;
                if (y + 1 < h && (skel.get(x, y + 1) & 0xff) != 0) lengthPx += 1.0;
                if (x + 1 < w && y + 1 < h && (skel.get(x + 1, y + 1) & 0xff) != 0) lengthPx += Math.sqrt(2.0);
                if (x - 1 >= 0 && y + 1 < h && (skel.get(x - 1, y + 1) & 0xff) != 0) lengthPx += Math.sqrt(2.0);
            }
        }
        return lengthPx * unitPerPixel;
    }

    /**
     * Skeletonize a binary mask where the intended foreground is the non-zero pixels.
     * Tries both polarities because ImageJ binary ops can be sensitive to background conventions.
     */
    private static ij.process.ByteProcessor skeletonizeForeground(ij.process.ByteProcessor mask) {
        int fg = countNonZero(mask);
        if (fg == 0) return mask;

        ij.process.ByteProcessor a = (ij.process.ByteProcessor) mask.duplicate();
        ij.process.BinaryProcessor ba = new ij.process.BinaryProcessor(a);
        ba.skeletonize();
        int skA = countNonZero(a);

        ij.process.ByteProcessor b = (ij.process.ByteProcessor) mask.duplicate();
        b.invert();
        ij.process.BinaryProcessor bb = new ij.process.BinaryProcessor(b);
        bb.skeletonize();
        b.invert(); // bring skeleton back to original polarity
        int skB = countNonZero(b);

        // Pick the result whose skeleton size is most plausible relative to original foreground.
        // A correct skeleton should not be larger than the original foreground.
        boolean aOk = skA > 0 && skA <= fg;
        boolean bOk = skB > 0 && skB <= fg;
        if (aOk && bOk) {
            return (skA <= skB) ? a : b;
        }
        if (aOk) return a;
        if (bOk) return b;
        // Fallback: choose smaller skeleton (usually less wrong)
        return (skA <= skB) ? a : b;
    }

    private static int countNonZero(ij.process.ByteProcessor ip) {
        int w = ip.getWidth();
        int h = ip.getHeight();
        int c = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((ip.get(x, y) & 0xff) != 0) c++;
        return c;
    }

    // flood fill 4-connected
    private static int floodFill4(ImageProcessor ip, int[] labels, int w, int h, int sx, int sy, int id) {
        int max = w * h;
        int[] qx = new int[max];
        int[] qy = new int[max];
        int qs = 0, qe = 0;

        qx[qe] = sx;
        qy[qe] = sy;
        qe++;
        labels[sy * w + sx] = id;

        int area = 0;
        while (qs < qe) {
            int x = qx[qs];
            int y = qy[qs];
            qs++;
            area++;

            // 4-neighbors
            if (x > 0) {
                int nx = x - 1, ny = y;
                int ni = ny * w + nx;
                if (labels[ni] == 0 && (ip.get(nx, ny) & 0xff) != 0) {
                    labels[ni] = id;
                    qx[qe] = nx;
                    qy[qe] = ny;
                    qe++;
                }
            }
            if (x + 1 < w) {
                int nx = x + 1, ny = y;
                int ni = ny * w + nx;
                if (labels[ni] == 0 && (ip.get(nx, ny) & 0xff) != 0) {
                    labels[ni] = id;
                    qx[qe] = nx;
                    qy[qe] = ny;
                    qe++;
                }
            }
            if (y > 0) {
                int nx = x, ny = y - 1;
                int ni = ny * w + nx;
                if (labels[ni] == 0 && (ip.get(nx, ny) & 0xff) != 0) {
                    labels[ni] = id;
                    qx[qe] = nx;
                    qy[qe] = ny;
                    qe++;
                }
            }
            if (y + 1 < h) {
                int nx = x, ny = y + 1;
                int ni = ny * w + nx;
                if (labels[ni] == 0 && (ip.get(nx, ny) & 0xff) != 0) {
                    labels[ni] = id;
                    qx[qe] = nx;
                    qy[qe] = ny;
                    qe++;
                }
            }
        }
        return area;
    }

    // --- IO + homography helpers (copied minimal from Perspective_Correct_Batch) ---

    private static List<File> listImages(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new ArrayList<File>();
        Arrays.sort(files);
        List<File> out = new ArrayList<File>();
        for (File f : files) {
            if (!f.isFile()) continue;
            String ext = extension(f.getName()).toLowerCase(Locale.ROOT);
            for (String ok : IMAGE_EXTS) {
                if (ext.equals(ok)) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    private static Map<String, File> indexPtsFiles(File dir) {
        Map<String, File> m = new HashMap<String, File>();
        File[] files = dir.listFiles();
        if (files == null) return m;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(PTS_EXT)) continue;
            String key = name.substring(0, name.length() - PTS_EXT.length());
            m.put(key, f);
        }
        return m;
    }

    private static File findPtsForImage(Map<String, File> ptsByKey, File imgFile) {
        File f = ptsByKey.get(imgFile.getName());
        if (f != null) return f;
        return ptsByKey.get(baseName(imgFile.getName()));
    }

    private static Point2D.Double[] read4Points(File ptsFile) throws IOException {
        List<Point2D.Double> pts = new ArrayList<Point2D.Double>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(ptsFile), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith("#")) continue;
                s = s.replace('\t', ' ');
                s = s.replace(',', ' ');
                String[] parts = s.trim().split("\\s+");
                if (parts.length < 2) continue;
                double x = Double.parseDouble(parts[0]);
                double y = Double.parseDouble(parts[1]);
                pts.add(new Point2D.Double(x, y));
            }
        } finally {
            br.close();
        }
        if (pts.size() != 4) throw new IOException("Expected 4 points in " + ptsFile.getName() + ", got " + pts.size());
        return pts.toArray(new Point2D.Double[4]);
    }

    private static Point2D.Double[] orderCornersTLTRBRBL(Point2D.Double[] in) {
        if (in.length != 4) throw new IllegalArgumentException("Need 4 points");
        Point2D.Double[] p = new Point2D.Double[]{in[0], in[1], in[2], in[3]};
        Arrays.sort(p, (a, b) -> {
            int cy = Double.compare(a.y, b.y);
            if (cy != 0) return cy;
            return Double.compare(a.x, b.x);
        });
        Point2D.Double t0 = p[0];
        Point2D.Double t1 = p[1];
        Point2D.Double b0 = p[2];
        Point2D.Double b1 = p[3];
        Point2D.Double tl = (t0.x <= t1.x) ? t0 : t1;
        Point2D.Double tr = (t0.x <= t1.x) ? t1 : t0;
        Point2D.Double bl = (b0.x <= b1.x) ? b0 : b1;
        Point2D.Double br = (b0.x <= b1.x) ? b1 : b0;
        return new Point2D.Double[]{tl, tr, br, bl};
    }

    private static int estimateWidth(Point2D.Double[] p) {
        double w1 = p[0].distance(p[1]);
        double w2 = p[3].distance(p[2]);
        return (int) Math.round((w1 + w2) / 2.0);
    }

    private static int estimateHeight(Point2D.Double[] p) {
        double h1 = p[0].distance(p[3]);
        double h2 = p[1].distance(p[2]);
        return (int) Math.round((h1 + h2) / 2.0);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "tif";
        return name.substring(dot + 1);
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static double[] computeHomography(Point2D.Double[] srcPts, Point2D.Double[] dstPts) {
        double[][] A = new double[8][8];
        double[] b = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = srcPts[i].x;
            double y = srcPts[i].y;
            double u = dstPts[i].x;
            double v = dstPts[i].y;
            int r = 2 * i;
            A[r][0] = x;
            A[r][1] = y;
            A[r][2] = 1;
            A[r][6] = -u * x;
            A[r][7] = -u * y;
            b[r] = u;
            A[r + 1][3] = x;
            A[r + 1][4] = y;
            A[r + 1][5] = 1;
            A[r + 1][6] = -v * x;
            A[r + 1][7] = -v * y;
            b[r + 1] = v;
        }
        double[] h = solveGaussian(A, b);
        return new double[]{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0};
    }

    private static double[] solveGaussian(double[][] A, double[] b) {
        int n = b.length;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double max = Math.abs(M[col][col]);
            for (int row = col + 1; row < n; row++) {
                double v = Math.abs(M[row][col]);
                if (v > max) {
                    max = v;
                    pivot = row;
                }
            }
            if (max == 0.0) throw new IllegalArgumentException("Singular transform");
            if (pivot != col) {
                double[] tmp = M[col];
                M[col] = M[pivot];
                M[pivot] = tmp;
            }
            double diag = M[col][col];
            for (int k = col; k <= n; k++) M[col][k] /= diag;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = M[row][col];
                if (factor == 0.0) continue;
                for (int k = col; k <= n; k++) M[row][k] -= factor * M[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = M[i][n];
        return x;
    }

    /** Perspective utilities. */
    private static class Perspective {
        static String normalizeInterp(String v) {
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.equals("bicubic") || s.equals("cubic")) return "Bicubic";
            if (s.equals("bilinear") || s.equals("linear")) return "Bilinear";
            if (s.equals("nearest") || s.equals("nearestneighbor") || s.equals("nn")) return "Nearest";
            throw new IllegalArgumentException("Unsupported interp=" + v);
        }

        static ImageProcessor warp(ImageProcessor src, int outW, int outH, double[] HdstToSrc, String interp) {
            ImageProcessor out = src.createProcessor(outW, outH);
            ImageProcessor srcFloat = src.convertToFloat();
            int method = ImageProcessor.BICUBIC;
            if (interp.equals("Bilinear")) method = ImageProcessor.BILINEAR;
            else if (interp.equals("Nearest")) method = ImageProcessor.NEAREST_NEIGHBOR;
            srcFloat.setInterpolationMethod(method);

            double h11 = HdstToSrc[0], h12 = HdstToSrc[1], h13 = HdstToSrc[2];
            double h21 = HdstToSrc[3], h22 = HdstToSrc[4], h23 = HdstToSrc[5];
            double h31 = HdstToSrc[6], h32 = HdstToSrc[7], h33 = HdstToSrc[8];
            int srcW = src.getWidth();
            int srcH = src.getHeight();

            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    double denom = h31 * x + h32 * y + h33;
                    if (denom == 0.0) {
                        putZero(out, x, y);
                        continue;
                    }
                    double sx = (h11 * x + h12 * y + h13) / denom;
                    double sy = (h21 * x + h22 * y + h23) / denom;
                    if (sx < 0 || sy < 0 || sx > (srcW - 1) || sy > (srcH - 1)) {
                        putZero(out, x, y);
                        continue;
                    }
                    if (out instanceof ij.process.FloatProcessor) {
                        out.putPixelValue(x, y, srcFloat.getInterpolatedValue(sx, sy));
                    } else {
                        out.putPixel(x, y, (int) Math.round(srcFloat.getInterpolatedValue(sx, sy)));
                    }
                }
            }
            return out;
        }

        static void putZero(ImageProcessor out, int x, int y) {
            if (out instanceof ij.process.FloatProcessor) out.putPixelValue(x, y, 0.0);
            else out.putPixel(x, y, 0);
        }

        static boolean save(ImagePlus imp, File outFile) {
            ij.io.FileSaver fs = new ij.io.FileSaver(imp);
            String ext = extension(outFile.getName()).toLowerCase(Locale.ROOT);
            String path = outFile.getAbsolutePath();
            if (ext.equals("tif") || ext.equals("tiff")) return fs.saveAsTiff(path);
            if (ext.equals("png")) return fs.saveAsPng(path);
            if (ext.equals("jpg") || ext.equals("jpeg")) return fs.saveAsJpeg(path);
            if (ext.equals("bmp")) return fs.saveAsBmp(path);
            return fs.saveAsTiff(path);
        }
    }
}
