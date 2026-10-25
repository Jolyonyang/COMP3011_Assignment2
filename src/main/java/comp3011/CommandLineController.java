/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. JiaoYang Yang (a3126438)
 *
Changes made:
*   - Included the frameProcessors list and accompanying getFrameProcessors()
*     method for gathering the FrameProcessor objects in command-line order in
*     the pipeline.
*   - Rewrote parse() to delegate to parseLongOption() and parseShortOptions()
*     so it supports both the --long-option and -stacked short option syntaxes.
*   - Incorporated all the 11 frame-effect options (short: -n -s -f -w -y -v -d
*     -j -m -b -p; long: --number-frames, --scratch-frames, --flicker-frames,
*     --black-and-white, --yellow-frames, --vignette-frames, --dust-frames,
*     --jitter-frames, --mottle-frames, --bleed-frames, --pepper-frames).
*   - Introduced isValidShortOption() in order to validate if stacked short
*     options are valid before they are applied.
*   - Further improved printHelp() with frame processor section with usage
*     example.
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video
 * player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to
 * the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private final List<FrameProcessor> frameProcessors = new ArrayList<>();
    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public List<FrameProcessor> getFrameProcessors() {
        return frameProcessors;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else if (arg.startsWith("-")) {
                if (errorMessage == null) {
                    errorMessage = "Unknown option: " + arg;
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (errorMessage == null) {
            if (videoFiles.size() > 1) {
                errorMessage = "Usage: VideoPlayer [options] [video-file]";
            } else if (videoFiles.size() == 1) {
                videoFile = new File(videoFiles.get(0));
                if (!videoFile.isFile()) {
                    errorMessage = "File not found: " + videoFile.getPath();
                    videoFile = null;
                }
            } else {
                if (!helpRequested) {
                    errorMessage = "No video file specified.";
                }
            }
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);
            case "--number-frames" -> frameProcessors.add(new FrameNumberer());
            case "--scratch-frames" -> frameProcessors.add(new FrameScratcher());
            case "--flicker-frames" -> frameProcessors.add(new FrameFlickerer());
            case "--black-and-white" -> frameProcessors.add(new FrameBlackAndWhiter());
            case "--yellow-frames" -> frameProcessors.add(new FrameYellower());
            case "--vignette-frames" -> frameProcessors.add(new FrameVignetter());
            case "--dust-frames" -> frameProcessors.add(new FrameDuster());
            case "--jitter-frames" -> frameProcessors.add(new FrameJitterer());
            case "--mottle-frames" -> frameProcessors.add(new FrameMottler());
            case "--bleed-frames" -> frameProcessors.add(new FrameBleeder());
            case "--pepper-frames" -> frameProcessors.add(new FramePepperer());
            default -> {
                if (errorMessage == null) {
                    errorMessage = "Unknown option: " + arg;
                }
            }
        }
    }

    private void parseShortOptions(String arg) {
        // Validate all characters first to ensure option stacking validity
        for (int i = 1; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (!isValidShortOption(c)) {
                if (errorMessage == null) {
                    errorMessage = "Unknown option: " + arg;
                }
                return;
            }
        }

        // Apply short options in order
        for (int i = 1; i < arg.length(); i++) {
            char c = arg.charAt(i);
            switch (c) {
                case 'h' -> helpRequested = true;
                case 'a' -> audioRequested = true;
                case 'x' -> maximiseRequested = true;
                case '1' -> setDisplayId(1);
                case '2' -> setDisplayId(2);
                case 'n' -> frameProcessors.add(new FrameNumberer());
                case 's' -> frameProcessors.add(new FrameScratcher());
                case 'f' -> frameProcessors.add(new FrameFlickerer());
                case 'w' -> frameProcessors.add(new FrameBlackAndWhiter());
                case 'y' -> frameProcessors.add(new FrameYellower());
                case 'v' -> frameProcessors.add(new FrameVignetter());
                case 'd' -> frameProcessors.add(new FrameDuster());
                case 'j' -> frameProcessors.add(new FrameJitterer());
                case 'm' -> frameProcessors.add(new FrameMottler());
                case 'b' -> frameProcessors.add(new FrameBleeder());
                case 'p' -> frameProcessors.add(new FramePepperer());
                default -> {
                    // Handled by validation above
                }
            }
        }
    }

    private boolean isValidShortOption(char c) {
        return c == 'h' || c == 'a' || c == 'x' || c == '1' || c == '2'
                || c == 'n' || c == 's' || c == 'f' || c == 'w' || c == 'y'
                || c == 'v' || c == 'd' || c == 'j' || c == 'm' || c == 'b'
                || c == 'p';
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
