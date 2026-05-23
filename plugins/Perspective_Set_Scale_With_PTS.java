import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Roi;
import ij.io.FileInfo;

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
 * Set a perspective-correct scale on an unrectified image, using a 4-point .pts sidecar.
 *
 * Draw a ruler line on the original image, enter known distance+unit.
 * Scale is stored on the image (properties) and optionally as a global default (Prefs).
 */
public class Perspective_Set_Scale_With_PTS implements ij.plugin.PlugIn {

    private static final String PROP_UNIT = "perspPts.unit";
    private static final String PROP_UNIT_PER_PX = "perspPts.unitPerRectPx";
    private static final String PREF_UNIT = "perspPts.global.unit";
    private static final String PREF_UNIT_PER_PX = "perspPts.global.unitPerRectPx";

    public void run(String arg) {
        Locale.setDefault(Locale.US);

        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("Perspective Set Scale", "No image");
            return;
        }

        Roi roi = imp.getRoi();
        if (!(roi instanceof Line)) {
            IJ.error("Perspective Set Scale", "Straight line selection required (use the line tool on the ruler)");
            return;
        }

        File ptsFile = findPtsFile(imp);
        if (ptsFile == null || !ptsFile.exists()) {
            IJ.error("Perspective Set Scale", "Missing .pts next to image (expected <base>.pts or <file>.pts)");
            return;
        }

        Point2D.Double[] src;
        try {
            src = orderCornersTLTRBRBL(read4Points(ptsFile));
        } catch (Exception e) {
            IJ.error("Perspective Set Scale", "Failed to read points: " + e.getMessage());
            return;
        }

        int outW = estimateWidth(src);
        int outH = estimateHeight(src);
        if (outW < 2 || outH < 2) {
            IJ.error("Perspective Set Scale", "Bad rectified size from points: " + outW + "x" + outH);
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
            IJ.error("Perspective Set Scale", "Degenerate points (homography failed): " + e.getMessage());
            return;
        }

        Line ln = (Line) roi;
        Point2D.Double p1 = applyHomography(HsrcToDst, ln.x1d, ln.y1d);
        Point2D.Double p2 = applyHomography(HsrcToDst, ln.x2d, ln.y2d);
        double rectLenPx = dist(p1.x, p1.y, p2.x, p2.y);
        if (!(rectLenPx > 0)) {
            IJ.error("Perspective Set Scale", "Invalid line length");
            return;
        }

        // Defaults
        String unit0 = getStringProp(imp, PROP_UNIT, "mm");
        if (unit0 == null || unit0.isEmpty()) unit0 = Prefs.get(PREF_UNIT, "mm");

        GenericDialog gd = new GenericDialog("Perspective Set Scale (PTS)");
        gd.addMessage("PTS: " + ptsFile.getName());
        gd.addMessage("Rectified size: " + outW + " x " + outH);
        gd.addMessage("Ruler line length (rectified px): " + IJ.d2s(rectLenPx, 3));
        gd.addNumericField("Known distance", 10.0, 6);
        gd.addStringField("Unit", unit0, 8);
        gd.addCheckbox("Save as global default", true);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        double known = gd.getNextNumber();
        String unit = gd.getNextString();
        boolean saveGlobal = gd.getNextBoolean();
        if (!(known > 0)) {
            IJ.error("Perspective Set Scale", "Known distance must be > 0");
            return;
        }
        if (unit == null || unit.trim().isEmpty()) unit = "mm";
        unit = unit.trim();

        double unitPerPx = known / rectLenPx;
        imp.setProperty(PROP_UNIT, unit);
        imp.setProperty(PROP_UNIT_PER_PX, "" + unitPerPx);

        if (saveGlobal) {
            Prefs.set(PREF_UNIT, unit);
            Prefs.set(PREF_UNIT_PER_PX, unitPerPx);
            Prefs.savePreferences();
        }

        IJ.log("Set perspective scale: 1 rect_px = " + IJ.d2s(unitPerPx, 6) + " " + unit);
    }

    private static File findPtsFile(ImagePlus imp) {
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.fileName == null) {
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

    /** Compute homography H mapping srcPts -> dstPts. */
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
}
