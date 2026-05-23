// Export_Rectangle_Corners_To_PTS
//
// Draw a rectangular selection, then run this script.
// It writes 4 corner points to a sidecar file: <imageBaseName>.pts
// Order: top-left, top-right, bottom-right, bottom-left
//
// If your selection is a 4-point polygon, it exports the 4 vertices, auto-ordered to TL/TR/BR/BL.

if (nImages==0) exit("Open an image first");

if (selectionType==-1)
    exit("Selection required (rectangle or 4-point polygon)");

title = getTitle();
imgDir = getDirectory("image");
if (imgDir=="") imgDir = getDirectory("home");

// build default .pts path next to image
base = title;
dot = lastIndexOf(base, ".");
if (dot!=-1) base = substring(base, 0, dot);
ptsPath = imgDir + base + ".pts";

// Ensure these exist as globals so functions can assign them
x1=0; y1=0;
x2=0; y2=0;
x3=0; y3=0;
x4=0; y4=0;

function orderTLTRBRBL(xs, ys) {
    // Input: 4 points in arbitrary order.
    // Output: returns array[8]: x1,y1,x2,y2,x3,y3,x4,y4 in TL,TR,BR,BL order.
    n = xs.length;
    if (n!=4) exit("Need 4 vertices, got " + n);

    // Make local copies
    xx = newArray(4);
    yy = newArray(4);
    for (i=0; i<4; i++) { xx[i]=xs[i]; yy[i]=ys[i]; }

    // sort indices by y then x (simple selection sort, n=4)
    idx = newArray(0,1,2,3);
    for (i=0; i<4; i++) {
        minj = i;
        for (j=i+1; j<4; j++) {
            a = idx[j];
            b = idx[minj];
            if (yy[a] < yy[b] || (yy[a]==yy[b] && xx[a] < xx[b])) minj = j;
        }
        tmp = idx[i]; idx[i]=idx[minj]; idx[minj]=tmp;
    }

    // top two: idx[0], idx[1]; bottom two: idx[2], idx[3]
    t0 = idx[0]; t1 = idx[1]; b0 = idx[2]; b1 = idx[3];

    // within each pair, sort by x
    if (xx[t0] <= xx[t1]) { tl=t0; tr=t1; } else { tl=t1; tr=t0; }
    if (xx[b0] <= xx[b1]) { bl=b0; br=b1; } else { bl=b1; br=b0; }

    return newArray(
        xx[tl], yy[tl],
        xx[tr], yy[tr],
        xx[br], yy[br],
        xx[bl], yy[bl]
    );
}

// collect 4 points
if (selectionType==0) {
    // rectangle
    getSelectionBounds(x, y, w, h);
    if (w<1 || h<1) exit("Empty rectangle");

    x1 = x;       y1 = y;
    x2 = x+w-1;   y2 = y;
    x3 = x+w-1;   y3 = y+h-1;
    x4 = x;       y4 = y+h-1;
} else {
    // polygon-like: require 4 vertices
    getSelectionCoordinates(xs, ys);
    ordered = orderTLTRBRBL(xs, ys);
    x1=ordered[0]; y1=ordered[1];
    x2=ordered[2]; y2=ordered[3];
    x3=ordered[4]; y3=ordered[5];
    x4=ordered[6]; y4=ordered[7];
}

// write file
s = "";
s = s + "# " + title + "\n";
s = s + "# TL TR BR BL\n";
s = s + x1 + " " + y1 + "\n";
s = s + x2 + " " + y2 + "\n";
s = s + x3 + " " + y3 + "\n";
s = s + x4 + " " + y4 + "\n";
File.saveString(s, ptsPath);

print("Wrote: " + ptsPath);
print("TL: " + x1 + "," + y1);
print("TR: " + x2 + "," + y2);
print("BR: " + x3 + "," + y3);
print("BL: " + x4 + "," + y4);
