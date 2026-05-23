// CLI entrypoint for Rectify_Scale_Skeleton_Measure_Batch plugin.
// Example:
//   ImageJ -batch macros/RectifyScaleSkeletonMeasureBatch.ijm "input=D:\\in output=D:\\out"

args = getArgument();
// Ensure plugins/ is on the classpath in batch mode.
call("java.lang.System.setProperty", "plugins.dir", getDirectory("imagej") + "plugins");
call("ij.Menus.updateImageJMenus");

// Use runPlugIn by class name.
call("ij.IJ.runPlugIn", "Rectify_Scale_Skeleton_Measure_Batch", args);
run("Quit");
