newImage("test", "8-bit black", 120, 120, 1);
setForegroundColor(255,255,255);
setLineWidth(2);
// draw a trapezoid-like shape
makeLine(10,10, 90,15); run("Draw");
makeLine(90,15, 85,90); run("Draw");
makeLine(85,90, 15,85); run("Draw");
makeLine(15,85, 10,10); run("Draw");
saveAs("PNG", getDirectory("imagej")+"\\_tmp_in\\test.png");
close();
