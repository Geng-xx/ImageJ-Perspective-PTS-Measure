// CLI entrypoint for Perspective_Correct_Batch plugin.
// Usage example:
//   ImageJ -batch macros/PerspectiveCorrectBatch.ijm "input=D:\\in,output=D:\\out,suffix=_rect,interp=Bicubic"

requires("1.52p");

args = getArgument();
if (args=="") {
    // If no args, plugin will show its own dialog.
    run("Perspective Correct Batch");
} else {
    run("Perspective Correct Batch", args);
}

// Ensure ImageJ exits after batch
run("Quit");
