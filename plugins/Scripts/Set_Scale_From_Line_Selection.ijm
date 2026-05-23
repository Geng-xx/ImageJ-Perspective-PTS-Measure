// Set_Scale_From_Line_Selection
//
// 1) On the (perspective-corrected) image, draw a straight line or segmented line
//    over a ruler/known-length feature.
// 2) Run this script. It will ask for the real-world length and unit, then set scale.

if (nImages==0) exit("Open an image first");
if (selectionType==-1) exit("Line selection required");

// Get pixel length of straight line or polyline
pix = 0;

// Try straight line API first
ok = 0;
getLine(xa, ya, xb, yb, lineWidth);
// If no line selection, xa..yb may be 0. We do a sanity check.
if (!(xa==0 && ya==0 && xb==0 && yb==0)) {
    dx = xb - xa;
    dy = yb - ya;
    pix = sqrt(dx*dx + dy*dy);
    ok = 1;
}

if (!ok) {
    // Fallback: segmented line or polygon with coordinates
    getSelectionCoordinates(xs, ys);
    if (xs.length<2) exit("Line selection required");
    for (i=0; i<xs.length-1; i++) {
        dx = xs[i+1] - xs[i];
        dy = ys[i+1] - ys[i];
        pix += sqrt(dx*dx + dy*dy);
    }
}

Dialog.create("Set Scale");
Dialog.addMessage("Pixel distance: " + d2s(pix, 3));
Dialog.addNumber("Known distance (real)", 10, 6, 12, "");
Dialog.addString("Unit", "mm", 10);
Dialog.addCheckbox("Global (apply to all images)", true);
Dialog.show();

known = Dialog.getNumber();
unit = Dialog.getString();
isGlobal = Dialog.getCheckbox();

if (known<=0) exit("Known distance must be > 0");
if (pix<=0) exit("Pixel distance must be > 0");

opts = "distance=" + pix + " known=" + known + " unit=" + unit;
if (isGlobal) opts = opts + " global";
run("Set Scale...", opts);

// Report
print("Set scale: " + known + " " + unit + " = " + d2s(pix,3) + " pixels");
print("=> 1 pixel = " + d2s(known/pix, 6) + " " + unit);
