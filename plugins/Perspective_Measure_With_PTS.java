import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.measure.ResultsTable;

import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Measure lengths on the original (unrectified) image, using a 4-point .pts sidecar
 * to compute a homography to a rectified plane.
 *
 * Workflow:
 * 1) Export 4 points to <imageBaseName>.pts (or <fileName>.pts)
 * 2) Draw a line on the ruler (known distance), run this plugin, enable "Set scale"
 * 3) Draw a line/polyline on the structure, run this plugin, enable "Measure"
 */
public class Perspective_Measure_With_PTS implements ij.plugin.PlugIn {

    private static final String PROP_UNIT = "perspPts.unit";
    private static final String PROP_UNIT_PER_PX = "perspPts.unitPerRectPx";
    private static final String PREF_UNIT = "perspPts.global.unit";
    private static final String PREF_UNIT_PER_PX = "perspPts.global.unitPerRectPx";

    public void run(String arg) {
        Locale.setDefault(Locale.US);

        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("Perspective Measure", "No image");
            return;
        }

        Roi roi = imp.getRoi();
        if (roi == null) {
            IJ.error("Perspective Measure", "Line or polyline selection required");
            return;
        }

        File ptsFile = findPtsFile(imp);
        if (ptsFile == null || !ptsFile.exists()) {
            IJ.error("Perspective Measure", "Missing .pts next to image (expected <base>.pts or <file>.pts)");
            return;
        }

        Point2D.Double[] src;
        try {
            src = orderCornersTLTRBRBL(read4Points(ptsFile));
        } catch (Exception e) {
            IJ.error("Perspective Measure", "Failed to read points: " + e.getMessage());
            return;
        }

        int outW = estimateWidth(src);
        int outH = estimateHeight(src);
        if (outW < 2 || outH < 2) {
            IJ.error("Perspective Measure", "Bad rectified size from points: " + outW + "x" + outH);
            return;
        }

        Point2D.Double[] dst = new Point2D.Double[]{
                new Point2D.Double(0, 0),
                new Point2D.Double(outW - 1, 0),
                new Point2D.Double(outW - 1, outH - 1),
                new Point2D.Double(0, outH - 1)
        };

        double[] HsrcToDst;
        try {
            HsrcToDst = computeHomography(src, dst);
        } catch (Exception e) {
            IJ.error("Perspective Measure", "Degenerate points (homography failed): " + e.getMessage());
            return;
        }

        // Get selection points in source image coords
        double[] xs;
        double[] ys;
        if (roi instanceof Line) {
            Line ln = (Line) roi;
            xs = new double[]{ln.x1d, ln.x2d};
            ys = new double[]{ln.y1d, ln.y2d};
        } else {
            // Works for polyline / polygon; for freehand this may be dense but fine.
            ij.process.FloatPolygon fp = roi.getFloatPolygon();
            if (fp == null || fp.npoints < 2) {
                IJ.error("Perspective Measure", "Selection must have at least 2 points");
                return;
            }
            xs = new double[fp.npoints];
            ys = new double[fp.npoints];
            for (int i = 0; i < fp.npoints; i++) {
                xs[i] = fp.xpoints[i];
                ys[i] = fp.ypoints[i];
            }
        }

        // Transform to rectified coords and compute polyline length in rectified pixels
        double rectLenPx = 0.0;
        double[] rx = new double[xs.length];
        double[] ry = new double[ys.length];
        for (int i = 0; i < xs.length; i++) {
            Point2D.Double p = applyHomography(HsrcToDst, xs[i], ys[i]);
            rx[i] = p.x;
            ry[i] = p.y;
            if (i > 0) {
                rectLenPx += dist(rx[i - 1], ry[i - 1], rx[i], ry[i]);
            }
        }

        // Existing scale (unit per rectified pixel)
        String unit = getStringProp(imp, PROP_UNIT, null);
        double unitPerPx = getDoubleProp(imp, PROP_UNIT_PER_PX, Double.NaN);
        boolean hasScale = Double.isFinite(unitPerPx) && unitPerPx > 0 && unit != null && !unit.isEmpty();

        // Fallback to global scale if image-specific scale isn't set
        if (!hasScale) {
            unit = Prefs.get(PREF_UNIT, "");
            unitPerPx = Prefs.get(PREF_UNIT_PER_PX, Double.NaN);
            hasScale = Double.isFinite(unitPerPx) && unitPerPx > 0 && unit != null && !unit.isEmpty();
        }

        ResultsTable rt = ResultsTable.getResultsTable();
        rt.incrementCounter();
        rt.addValue("file", imageName(imp));
        rt.addValue("rect_px", rectLenPx);
        if (hasScale) {
            rt.addValue("length_" + unit, rectLenPx * unitPerPx);
        }
        rt.show("Results");

        if (hasScale) {
            IJ.log("Measured: " + IJ.d2s(rectLenPx * unitPerPx, 4) + " " + unit + " (rect_px=" + IJ.d2s(rectLenPx, 3) + ")");
        } else {
            IJ.log("Measured: rect_px=" + IJ.d2s(rectLenPx, 3) + " (scale not set; run 'Perspective Set Scale With PTS')");
        }
    }

    private static String imageName(ImagePlus imp) {
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi != null && fi.fileName != null) return fi.fileName;
        return imp.getTitle();
    }

    private static File findPtsFile(ImagePlus imp) {
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.fileName == null) {
            // Try last directory
            String dir = IJ.getDirectory("image");
            if (dir == null) return null;
            String title = imp.getTitle();
            String base = baseName(title);
            File f1 = new File(dir, base + ".pts");
            if (f1.exists()) return f1;
            File f2 = new File(dir, title + ".pts");
            if (f2.exists()) return f2;
            return f1;
        }

        File dir = new File(fi.directory);
        String base = baseName(fi.fileName);
        File f1 = new File(dir, base + ".pts");
        if (f1.exists()) return f1;
        File f2 = new File(dir, fi.fileName + ".pts");
        if (f2.exists()) return f2;
        return f1;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String getStringProp(ImagePlus imp, String key, String def) {
        Object v = imp.getProperty(key);
        if (v == null) return def;
        String s = v.toString();
        return s.isEmpty() ? def : s;
    }

    private static double getDoubleProp(ImagePlus imp, String key, double def) {
        Object v = imp.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static Point2D.Double applyHomography(double[] H, double x, double y) {
        double h11 = H[0], h12 = H[1], h13 = H[2];
        double h21 = H[3], h22 = H[4], h23 = H[5];
        double h31 = H[6], h32 = H[7], h33 = H[8];
        double denom = h31 * x + h32 * y + h33;
        if (denom == 0.0) return new Point2D.Double(Double.NaN, Double.NaN);
        double u = (h11 * x + h12 * y + h13) / denom;
        double v = (h21 * x + h22 * y + h23) / denom;
        return new Point2D.Double(u, v);
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
        if (pts.size() != 4) throw new IOException("Expected 4 points, got " + pts.size());
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

    /**
     * Compute homography H mapping srcPts -> dstPts.
     * Returns 3x3 matrix flattened row-major.
     */
    private static double[] computeHomography(Point2D.Double[] srcPts, Point2D.Double[] dstPts) {
        if (srcPts.length != 4 || dstPts.length != 4)
            throw new IllegalArgumentException("Need exactly 4 src and 4 dst points");

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
            A[r][3] = 0;
            A[r][4] = 0;
            A[r][5] = 0;
            A[r][6] = -u * x;
            A[r][7] = -u * y;
            b[r] = u;

            A[r + 1][0] = 0;
            A[r + 1][1] = 0;
            A[r + 1][2] = 0;
            A[r + 1][3] = x;
            A[r + 1][4] = y;
            A[r + 1][5] = 1;
            A[r + 1][6] = -v * x;
            A[r + 1][7] = -v * y;
            b[r + 1] = v;
        }
        double[] h = solveGaussian(A, b);
        return new double[]{
                h[0], h[1], h[2],
                h[3], h[4], h[5],
                h[6], h[7], 1.0
        };
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
                for (int k = col; k <= n; k++) {
                    M[row][k] -= factor * M[col][k];
                }
            }
        }

        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = M[i][n];
        return x;
    }
}
