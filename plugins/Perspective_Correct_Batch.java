import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

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
 * Batch perspective correction using 4-point homography per image.
 *
 * Input: a folder of images, and for each image a sidecar text file with the same base name
 * and extension ".pts" containing 4 points (x y per line, or "x,y"). Points are in source
 * image coordinates (pixels).
 *
 * Output: corrected images written to output folder.
 */
public class Perspective_Correct_Batch implements PlugIn {

    // Sidecar file extension for points
    private static final String PTS_EXT = ".pts";

    // Supported image extensions for folder scan
    private static final String[] IMAGE_EXTS = new String[]{"tif", "tiff", "png", "jpg", "jpeg", "bmp", "gif"};

    public void run(String arg) {
        Locale.setDefault(Locale.US); // avoid comma decimal issues in parsing

        IJ.log("Perspective Correct (Batch) starting...");

        Args a;
        try {
            a = parseArgs(arg);
        } catch (IllegalArgumentException e) {
            IJ.error("Perspective Correct (Batch)", e.getMessage());
            return;
        }

        if (a.interactive) {
            a = showDialog(a);
            if (a == null) return;
        }

        IJ.log("Args: input=" + a.inputDir + ", output=" + a.outputDir + ", suffix=" + a.suffix + ", interp=" + a.interpolation);

        File inDir = new File(a.inputDir);
        File outDir = new File(a.outputDir);
        if (!inDir.isDirectory()) {
            IJ.error("Perspective Correct (Batch)", "Input is not a directory: " + a.inputDir);
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            IJ.error("Perspective Correct (Batch)", "Failed to create output directory: " + a.outputDir);
            return;
        }

        Map<String, File> ptsByBase = indexPtsFiles(inDir);
        List<File> images = listImages(inDir);

        if (images.isEmpty()) {
            IJ.log("No images found under: " + inDir.getAbsolutePath());
            return;
        }

        int processed = 0;
        int skipped = 0;
        IJ.log("Perspective Correct (Batch) - input=" + inDir.getAbsolutePath());
        IJ.log("Perspective Correct (Batch) - output=" + outDir.getAbsolutePath());
        IJ.log("Perspective Correct (Batch) - sidecar points: *" + PTS_EXT);

        for (int i = 0; i < images.size(); i++) {
            File imgFile = images.get(i);
            String base = baseName(imgFile.getName());
            File ptsFile = ptsByBase.get(imgFile.getName());
            if (ptsFile == null) ptsFile = ptsByBase.get(base);
            if (ptsFile == null) {
                IJ.log("SKIP (missing .pts): " + imgFile.getName());
                skipped++;
                continue;
            }

            IJ.showStatus("Processing " + (i + 1) + "/" + images.size() + ": " + imgFile.getName());
            IJ.showProgress(i + 1, images.size());

            try {
                Point2D.Double[] src = read4Points(ptsFile);
                src = orderCornersTLTRBRBL(src);
                IJ.log("Processing: " + imgFile.getName() + " pts=" + ptsFile.getName() + " "
                        + fmtPt(src[0]) + " " + fmtPt(src[1]) + " " + fmtPt(src[2]) + " " + fmtPt(src[3]));
                ImagePlus imp = IJ.openImage(imgFile.getAbsolutePath());
                if (imp == null) {
                    IJ.log("SKIP (open failed): " + imgFile.getName());
                    skipped++;
                    continue;
                }

                // Auto output size: average of opposite side lengths
                int outW = estimateWidth(src);
                int outH = estimateHeight(src);
                IJ.log("Estimated output size: " + outW + "x" + outH);
                if (outW < 1 || outH < 1) {
                    imp.close();
                    IJ.log("SKIP (bad output size): " + imgFile.getName());
                    skipped++;
                    continue;
                }

                Point2D.Double[] dst = new Point2D.Double[]{
                        new Point2D.Double(0, 0),
                        new Point2D.Double(outW - 1, 0),
                        new Point2D.Double(outW - 1, outH - 1),
                        new Point2D.Double(0, outH - 1)
                };

                // We map dst->src (inverse mapping) to sample source for each output pixel.
                double[] H = computeHomography(dst, src);
                IJ.log("H(dst->src)= [" + H[0] + "," + H[1] + "," + H[2] + "; " + H[3] + "," + H[4] + "," + H[5] + "; " + H[6] + "," + H[7] + "," + H[8] + "]");
                ImageProcessor out = warpPerspective(imp.getProcessor(), outW, outH, H, a.interpolation);

                ImagePlus outImp = new ImagePlus(imp.getTitle(), out);
                outImp.setCalibration(imp.getCalibration());

                String outName = base + a.suffix + "." + extension(imgFile.getName());
                File outFile = new File(outDir, outName);
                boolean ok = save(outImp, outFile);
                IJ.log((ok ? "OK" : "FAIL") + " save: " + outFile.getAbsolutePath());
                imp.close();
                outImp.close();

                if (ok) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                IJ.log("SKIP (error): " + imgFile.getName() + " -> " + e.getMessage());
                skipped++;
            }
        }

        IJ.showProgress(1.0);
        IJ.log("Done. processed=" + processed + ", skipped=" + skipped);
    }

    private static class Args {
        String inputDir;
        String outputDir;
        String suffix = "_rect";
        String interpolation = "Bicubic"; // matches macro naming
        boolean interactive = false;
    }

    private static Args parseArgs(String arg) {
        Args a = new Args();
        if (arg == null) arg = "";
        arg = arg.trim();
        if (arg.isEmpty()) {
            a.interactive = true;
            return a;
        }

        // Robust key=value parsing. ImageJ option strings are often space-separated (not comma-separated),
        // and paths may include characters that look like separators.
        // Supported forms:
        //   input=C:\in,output=C:\out,suffix=_rect,interp=Bicubic
        //   input=C:\in output=C:\out suffix=_rect interp=Bicubic
        String raw = arg;
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?i)\\b(input|output|suffix|interp|interpolation)="
        );
        java.util.regex.Matcher m = pat.matcher(raw);
        List<Integer> starts = new ArrayList<Integer>();
        List<String> keys = new ArrayList<String>();
        while (m.find()) {
            starts.add(m.start());
            keys.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        if (starts.isEmpty()) throw new IllegalArgumentException("Bad args: " + raw);

        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            String key = keys.get(i);
            int keyEnd = raw.indexOf('=', start);
            int valStart = keyEnd + 1;
            int valEnd = (i + 1 < starts.size()) ? starts.get(i + 1) : raw.length();
            String val = raw.substring(valStart, valEnd);
            // trim common separators that may precede next key
            val = val.trim();
            while (val.endsWith(",") || val.endsWith(";")) val = val.substring(0, val.length() - 1).trim();

            if (key.equals("input")) a.inputDir = val;
            else if (key.equals("output")) a.outputDir = val;
            else if (key.equals("suffix")) a.suffix = val;
            else if (key.equals("interp") || key.equals("interpolation")) a.interpolation = normalizeInterp(val);
        }

        if (a.inputDir == null || a.inputDir.trim().isEmpty())
            throw new IllegalArgumentException("Missing input=... directory");
        if (a.outputDir == null || a.outputDir.trim().isEmpty())
            throw new IllegalArgumentException("Missing output=... directory");
        return a;
    }

    private static Args showDialog(Args a) {
        GenericDialog gd = new GenericDialog("Perspective Correct (Batch)");

        String in = a.inputDir;
        if (in == null) in = OpenDialog.getDefaultDirectory();
        gd.addStringField("Input folder", in == null ? "" : in, 60);
        gd.addStringField("Output folder", a.outputDir == null ? "" : a.outputDir, 60);
        gd.addMessage("Sidecar points file: <imageBaseName>" + PTS_EXT + " (4 lines: x y)");
        gd.addStringField("Output suffix", a.suffix, 20);
        gd.addChoice("Interpolation", new String[]{"Bicubic", "Bilinear", "Nearest"}, a.interpolation);
        gd.showDialog();
        if (gd.wasCanceled()) return null;

        Args out = new Args();
        out.inputDir = gd.getNextString().trim();
        out.outputDir = gd.getNextString().trim();
        out.suffix = gd.getNextString();
        out.interpolation = gd.getNextChoice();
        out.interactive = false;
        return out;
    }

    private static String normalizeInterp(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("bicubic") || s.equals("cubic")) return "Bicubic";
        if (s.equals("bilinear") || s.equals("linear")) return "Bilinear";
        if (s.equals("nearest") || s.equals("nearestneighbor") || s.equals("nn")) return "Nearest";
        throw new IllegalArgumentException("Unsupported interp=" + v + " (use Bicubic/Bilinear/Nearest)");
    }

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
            // Support both: <base>.pts and <file.ext>.pts
            String key = name.substring(0, name.length() - PTS_EXT.length());
            m.put(key, f);
        }
        return m;
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

        if (pts.size() != 4) {
            throw new IOException("Expected 4 points in " + ptsFile.getName() + ", got " + pts.size());
        }
        return pts.toArray(new Point2D.Double[4]);
    }

    /**
     * Accepts 4 corners in arbitrary order and returns them ordered:
     * top-left, top-right, bottom-right, bottom-left.
     */
    private static Point2D.Double[] orderCornersTLTRBRBL(Point2D.Double[] in) {
        if (in.length != 4) throw new IllegalArgumentException("Need 4 points");
        Point2D.Double[] p = new Point2D.Double[]{in[0], in[1], in[2], in[3]};

        // Sort by y then x to split into top/bottom pairs
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
        // average of top and bottom edges: p0->p1 and p3->p2
        double w1 = p[0].distance(p[1]);
        double w2 = p[3].distance(p[2]);
        return (int) Math.round((w1 + w2) / 2.0);
    }

    private static int estimateHeight(Point2D.Double[] p) {
        // average of left and right edges: p0->p3 and p1->p2
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

        // Solve A*h = b for h = [h11 h12 h13 h21 h22 h23 h31 h32]^T, with h33=1.
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

    private static String fmtPt(Point2D.Double p) {
        return String.format(Locale.US, "(%.2f,%.2f)", p.x, p.y);
    }

    /** Basic Gaussian elimination with partial pivoting. */
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
            if (max == 0.0) throw new IllegalArgumentException("Singular transform (degenerate points)");
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

    private static ImageProcessor warpPerspective(ImageProcessor src, int outW, int outH, double[] HdstToSrc, String interp) {
        ImageProcessor out = src.createProcessor(outW, outH);

        // Convert to float for interpolation sampling; keep output type same as input.
        ImageProcessor srcFloat = src.convertToFloat();
        if (!(out instanceof ij.process.ColorProcessor)) {
            // Only for scalar processors.
            IJ.log("Warp: out=" + outW + "x" + outH + ", src=" + src.getWidth() + "x" + src.getHeight() + ", method=" + interp);
        }

        int method = ImageProcessor.BICUBIC;
        if (interp.equals("Bilinear")) method = ImageProcessor.BILINEAR;
        else if (interp.equals("Nearest")) method = ImageProcessor.NEAREST_NEIGHBOR;
        srcFloat.setInterpolationMethod(method);

        double h11 = HdstToSrc[0], h12 = HdstToSrc[1], h13 = HdstToSrc[2];
        double h21 = HdstToSrc[3], h22 = HdstToSrc[4], h23 = HdstToSrc[5];
        double h31 = HdstToSrc[6], h32 = HdstToSrc[7], h33 = HdstToSrc[8];

        int srcW = src.getWidth();
        int srcH = src.getHeight();

        // Use NaN outside for float; then clamp to 0 for integer outputs.
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double denom = h31 * x + h32 * y + h33;
                if (denom == 0.0) {
                    putZero(out, x, y);
                    continue;
                }
                double sx = (h11 * x + h12 * y + h13) / denom;
                double sy = (h21 * x + h22 * y + h23) / denom;

                // outside -> 0
                if (sx < 0 || sy < 0 || sx > (srcW - 1) || sy > (srcH - 1)) {
                    putZero(out, x, y);
                    continue;
                }

                if (out instanceof ij.process.ColorProcessor) {
                    int[] rgb = new int[3];
                    rgb = srcFloat.getPixel((int) Math.round(sx), (int) Math.round(sy), rgb);
                    // ColorProcessor interpolation API isn't exposed uniformly; fall back to nearest sample.
                    // For best color quality, run on RGB images that are already rectified reasonably.
                    out.putPixel(x, y, rgb);
                } else {
                    double v = srcFloat.getInterpolatedValue(sx, sy);
                    if (out instanceof ij.process.FloatProcessor) {
                        out.putPixelValue(x, y, v);
                    } else {
                        out.putPixel(x, y, (int) Math.round(v));
                    }
                }
            }
        }
        return out;
    }

    private static void putZero(ImageProcessor out, int x, int y) {
        if (out instanceof ij.process.ColorProcessor) out.putPixel(x, y, new int[]{0, 0, 0});
        else if (out instanceof ij.process.FloatProcessor) out.putPixelValue(x, y, 0.0);
        else out.putPixel(x, y, 0);
    }

    private static boolean save(ImagePlus imp, File outFile) {
        String path = outFile.getAbsolutePath();
        String ext = extension(outFile.getName()).toLowerCase(Locale.ROOT);
        FileSaver fs = new FileSaver(imp);
        if (ext.equals("tif") || ext.equals("tiff")) return fs.saveAsTiff(path);
        if (ext.equals("png")) return fs.saveAsPng(path);
        if (ext.equals("jpg") || ext.equals("jpeg")) return fs.saveAsJpeg(path);
        if (ext.equals("bmp")) return fs.saveAsBmp(path);
        // Default fallback
        return fs.saveAsTiff(path);
    }
}
