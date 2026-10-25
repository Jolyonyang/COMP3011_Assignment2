/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. COMP3011 Student (Assignment 2 Multi-Threaded Video Player)
 *
* Attribution / Acknowledgments
*   - Changed to use ThreadLocal storage instead of instance pixel buffer
*     so the use of data races is no longer a problem while processing frames concurrently using the CLO1 pipeline.
*
* Copyright 2026 by Simon Ratcliffe
*/
package comp3011;

import java.nio.ByteBuffer;
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Randomly shifts each of the image frames such that it mimics an unstable passage of film. The projectors used in
 * the older days for projecting the film had the sprockets which always had a little bit of space in them, which was
 * responsible for the jitter in the frames as the film would get projected. Hence, this effect, even though small
 * and simple, is quite effective.
 *
 * <p>
 * A random frame sequence, which is deterministic, selects both vertical and horizontal offsets. The method
 * {@link #jitterFrame(ByteBuffer, int, int)} simply shifts the original pixels to the new location and replaces
 * the exposed parts with black.
 * </p>
 */
public class FrameJitterer extends FrameProcessor {
    private static final int SEED_SALT = 0x4a495454;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;
    private final ThreadLocal<byte[]> threadLocalSourcePixels = new ThreadLocal<>();
    private byte[] blackRow;

    public FrameJitterer() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex + SEED_SALT);

        int widthBytes = videoInfo.imageWidth() * videoInfo.imageChannels();
        blackRow = new byte[widthBytes];
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameJitterer requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameJitterer requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameJitterer requires at least one image channel");
        }

        Random random = new Random(seedForFrame(info.frameNumber()));
        int yOffset = chooseVerticalOffset(random);
        int xOffset = chooseHorizontalOffset(random);

        if (xOffset == 0 && yOffset == 0) {
            return;
        }

        jitterFrame(pixels, xOffset, yOffset);
    }

    private long seedForFrame(int frameNumber) {
        long seed = Integer.toUnsignedLong(mediaSeed) + Integer.toUnsignedLong(frameNumber);

        // Mix adjacent frame numbers so Random's first samples are not correlated
        // across consecutive frames.
        seed ^= seed >>> 30;
        seed *= 0xbf58476d1ce4e5b9L;
        seed ^= seed >>> 27;
        seed *= 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        return seed;
    }

    private int chooseVerticalOffset(Random random) {
        double sample = random.nextDouble();
        if (sample < 0.02) {
            return -2;
        }
        if (sample < 0.10) {
            return -1;
        }
        if (sample < 0.90) {
            return 0;
        }
        if (sample < 0.98) {
            return 1;
        }
        return 2;
    }

    private int chooseHorizontalOffset(Random random) {
        double sample = random.nextDouble();
        if (sample < 0.02) {
            return -1;
        }
        if (sample < 0.98) {
            return 0;
        }
        return 1;
    }

    private void jitterFrame(ByteBuffer pixels, int xOffset, int yOffset) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int frameBytes = stride * height;
        int widthBytes = width * channels;

        byte[] sourcePixels = threadLocalSourcePixels.get();
        if (sourcePixels == null || sourcePixels.length < frameBytes) {
            sourcePixels = new byte[frameBytes];
            threadLocalSourcePixels.set(sourcePixels);
        }

        ByteBuffer source = pixels.duplicate();
        source.clear();
        source.get(sourcePixels, 0, frameBytes);

        for (int y = 0; y < height; y++) {
            ByteBuffer destinationRow = pixels.duplicate();
            destinationRow.clear();
            destinationRow.position(y * stride);
            destinationRow.put(blackRow, 0, widthBytes);
        }

        int sourceYStart = Math.max(0, -yOffset);
        int destinationYStart = Math.max(0, yOffset);
        int rowsToCopy = height - Math.abs(yOffset);

        int sourceXStart = Math.max(0, -xOffset);
        int destinationXStart = Math.max(0, xOffset);
        int pixelsToCopy = width - Math.abs(xOffset);

        int sourceXByteStart = sourceXStart * channels;
        int destinationXByteStart = destinationXStart * channels;
        int rowBytesToCopy = pixelsToCopy * channels;

        for (int row = 0; row < rowsToCopy; row++) {
            int sourceIndex = (sourceYStart + row) * stride + sourceXByteStart;
            int destinationIndex = (destinationYStart + row) * stride + destinationXByteStart;

            ByteBuffer destination = pixels.duplicate();
            destination.clear();
            destination.position(destinationIndex);
            destination.put(sourcePixels, sourceIndex, rowBytesToCopy);
        }
    }
}
