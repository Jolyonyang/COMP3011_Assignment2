/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. JiaoYang Yang (a3126438)
 *
 * Attribution / Acknowledgments:
 *   - Comprehensive test suite for CommandLineController verifying option stacking,
 *     processor order preservation, error handling and exact help text specification.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;

public class CommandLineControllerTest {

    @Test
    public void testStackedEquivalentCommands() {
        // The assignment explicitly specifies that these commands are all equivalent:
        // VideoPlayer --jitter-frames --flicker-frames --dust-frames --dust-frames --number-frames movies.mp4
        // VideoPlayer -j -f -d -d -n movies.mp4
        // VideoPlayer -jfddn movies.mp4
        // VideoPlayer -jf --dust-frames -d --number-frames movies.mp4

        String[] cmd1 = {"--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames", "--number-frames"};
        String[] cmd2 = {"-j", "-f", "-d", "-d", "-n"};
        String[] cmd3 = {"-jfddn"};
        String[] cmd4 = {"-jf", "--dust-frames", "-d", "--number-frames"};

        CommandLineController c1 = new CommandLineController(cmd1);
        CommandLineController c2 = new CommandLineController(cmd2);
        CommandLineController c3 = new CommandLineController(cmd3);
        CommandLineController c4 = new CommandLineController(cmd4);

        for (CommandLineController c : List.of(c1, c2, c3, c4)) {
            List<FrameProcessor> processors = c.getFrameProcessors();
            assertEquals(5, processors.size());
            assertTrue(processors.get(0) instanceof FrameJitterer);
            assertTrue(processors.get(1) instanceof FrameFlickerer);
            assertTrue(processors.get(2) instanceof FrameDuster);
            assertTrue(processors.get(3) instanceof FrameDuster);
            assertTrue(processors.get(4) instanceof FrameNumberer);
        }
    }

    @Test
    public void testExampleAllProcessorsSequence() {
        // Example from spec: -nssnfwyvdjmbp
        String[] args = {"-nssnfwyvdjmbp"};
        CommandLineController c = new CommandLineController(args);
        List<FrameProcessor> processors = c.getFrameProcessors();

        assertEquals(13, processors.size());
        assertTrue(processors.get(0) instanceof FrameNumberer);
        assertTrue(processors.get(1) instanceof FrameScratcher);
        assertTrue(processors.get(2) instanceof FrameScratcher);
        assertTrue(processors.get(3) instanceof FrameNumberer);
        assertTrue(processors.get(4) instanceof FrameFlickerer);
        assertTrue(processors.get(5) instanceof FrameBlackAndWhiter);
        assertTrue(processors.get(6) instanceof FrameYellower);
        assertTrue(processors.get(7) instanceof FrameVignetter);
        assertTrue(processors.get(8) instanceof FrameDuster);
        assertTrue(processors.get(9) instanceof FrameJitterer);
        assertTrue(processors.get(10) instanceof FrameMottler);
        assertTrue(processors.get(11) instanceof FrameBleeder);
        assertTrue(processors.get(12) instanceof FramePepperer);
    }

    @Test
    public void testAllLongOptions() {
        String[] args = {
            "--number-frames",
            "--scratch-frames",
            "--flicker-frames",
            "--black-and-white",
            "--yellow-frames",
            "--vignette-frames",
            "--dust-frames",
            "--jitter-frames",
            "--mottle-frames",
            "--bleed-frames",
            "--pepper-frames"
        };
        CommandLineController c = new CommandLineController(args);
        List<FrameProcessor> processors = c.getFrameProcessors();

        assertEquals(11, processors.size());
        assertTrue(processors.get(0) instanceof FrameNumberer);
        assertTrue(processors.get(1) instanceof FrameScratcher);
        assertTrue(processors.get(2) instanceof FrameFlickerer);
        assertTrue(processors.get(3) instanceof FrameBlackAndWhiter);
        assertTrue(processors.get(4) instanceof FrameYellower);
        assertTrue(processors.get(5) instanceof FrameVignetter);
        assertTrue(processors.get(6) instanceof FrameDuster);
        assertTrue(processors.get(7) instanceof FrameJitterer);
        assertTrue(processors.get(8) instanceof FrameMottler);
        assertTrue(processors.get(9) instanceof FrameBleeder);
        assertTrue(processors.get(10) instanceof FramePepperer);
    }

    @Test
    public void testStackedStandardOptions() throws Exception {
        File tempFile = File.createTempFile("sample_video", ".mp4");
        tempFile.deleteOnExit();

        CommandLineController c = new CommandLineController(new String[]{"-ax1", tempFile.getAbsolutePath()});
        assertTrue(c.isAudioRequested());
        assertTrue(c.isMaximiseRequested());
        assertEquals(1, c.getDisplayId());
        assertNull(c.getErrorMessage());
        assertTrue(c.shouldLaunchApplication());
        assertEquals(tempFile.getAbsoluteFile(), c.getVideoFile());
    }

    @Test
    public void testNoVideoFileSpecified() {
        CommandLineController c = new CommandLineController(new String[]{"-a"});
        assertEquals("No video file specified.", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
        assertFalse(c.shouldLaunchApplication());
    }

    @Test
    public void testHelpOption() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outContent));
            CommandLineController c = new CommandLineController(new String[]{"--help"});
            assertEquals(0, c.getExitCode());
            assertNull(c.getErrorMessage());
            String output = outContent.toString();
            assertTrue(output.contains("Usage: VideoPlayer [options] [video-file]"));
            assertTrue(output.contains("Frame processors:"));
            assertTrue(output.contains("-nssnfwyvdjmbp"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testConflictingDisplayOptions() {
        CommandLineController c = new CommandLineController(new String[]{"-1", "-2"});
        assertEquals("Only one display option can be used", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
        assertFalse(c.shouldLaunchApplication());
    }

    @Test
    public void testConflictingDisplayStackedOptions() {
        CommandLineController c = new CommandLineController(new String[]{"-12"});
        assertEquals("Only one display option can be used", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
    }

    @Test
    public void testUnknownLongOption() {
        CommandLineController c = new CommandLineController(new String[]{"--unknown-opt"});
        assertEquals("Unknown option: --unknown-opt", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
    }

    @Test
    public void testUnknownShortOption() {
        CommandLineController c = new CommandLineController(new String[]{"-z"});
        assertEquals("Unknown option: -z", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
    }

    @Test
    public void testUnknownStackedOption() {
        CommandLineController c = new CommandLineController(new String[]{"-jfz"});
        assertEquals("Unknown option: -jfz", c.getErrorMessage());
        assertEquals(1, c.getExitCode());
    }
}
