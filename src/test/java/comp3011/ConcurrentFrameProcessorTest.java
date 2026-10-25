/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. JiaoYang Yang (a3126438)
 *
 * Attribution / Acknowledgments:
 *   - Concurrency unit test verifying thread-safety and pixel-identical determinism
 *     across concurrent threads (CLO1 & CLO2).
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

public class ConcurrentFrameProcessorTest {

    private Frame createTestFrame(int width, int height) {
        int channels = 3;
        int stride = width * channels;
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
        frame.imageStride = stride;
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * height);
        // Fill deterministic gradient pattern
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = y * stride + x * channels;
                buffer.put(base, (byte) ((x * 7 + y * 13) % 256));
                buffer.put(base + 1, (byte) ((x * 17 + y * 5) % 256));
                buffer.put(base + 2, (byte) ((x * 3 + y * 23) % 256));
            }
        }
        buffer.clear();
        frame.image = new java.nio.Buffer[]{buffer};
        return frame;
    }

    private byte[] extractBytes(Frame frame) {
        ByteBuffer bb = (ByteBuffer) frame.image[0];
        bb.clear();
        byte[] bytes = new byte[bb.capacity()];
        bb.get(bytes);
        bb.clear();
        return bytes;
    }

    @Test
    public void testConcurrentFrameJittererDeterminism() throws Exception {
        int width = 320;
        int height = 240;
        InfoVideo infoVideo = new InfoVideo(
                "test_media", 100, width, height, Frame.DEPTH_UBYTE, 3, width * 3, 24.0, 24, 41666, 0);

        FrameJitterer processor = new FrameJitterer();
        processor.initialise(infoVideo);

        // Compute baseline sequentially for 10 frames
        List<byte[]> expected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Frame frame = createTestFrame(width, height);
            processor.process(frame, new InfoFrame(i, i * 41666L));
            expected.add(extractBytes(frame));
        }

        // Now run concurrently across 8 worker threads on the exact same shared processor instance
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<byte[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int frameNum = i;
            tasks.add(() -> {
                Frame frame = createTestFrame(width, height);
                processor.process(frame, new InfoFrame(frameNum, frameNum * 41666L));
                return extractBytes(frame);
            });
        }

        List<Future<byte[]>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (int i = 0; i < 10; i++) {
            byte[] actual = futures.get(i).get();
            assertArrayEquals(expected.get(i), actual, "Frame " + i + " output must be identical");
        }
    }

    @Test
    public void testConcurrentFrameBleederDeterminism() throws Exception {
        int width = 160;
        int height = 120;
        InfoVideo infoVideo = new InfoVideo(
                "test_media", 50, width, height, Frame.DEPTH_UBYTE, 3, width * 3, 24.0, 24, 41666, 0);

        FrameBleeder singleProcessor = new FrameBleeder();
        singleProcessor.initialise(infoVideo);

        List<byte[]> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Frame frame = createTestFrame(width, height);
            singleProcessor.process(frame, new InfoFrame(i, i * 41666L));
            expected.add(extractBytes(frame));
        }

        FrameBleeder concurrentProcessor = new FrameBleeder();
        concurrentProcessor.initialise(infoVideo);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<byte[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int frameNum = i;
            tasks.add(() -> {
                Frame frame = createTestFrame(width, height);
                concurrentProcessor.process(frame, new InfoFrame(frameNum, frameNum * 41666L));
                return extractBytes(frame);
            });
        }

        List<Future<byte[]>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (int i = 0; i < 8; i++) {
            byte[] actual = futures.get(i).get();
            assertArrayEquals(expected.get(i), actual, "Bleeder frame " + i + " must match sequential baseline");
        }
    }

    @Test
    public void testConcurrentFrameMottlerDeterminism() throws Exception {
        int width = 160;
        int height = 120;
        InfoVideo infoVideo = new InfoVideo(
                "test_media", 50, width, height, Frame.DEPTH_UBYTE, 3, width * 3, 24.0, 24, 41666, 0);

        FrameMottler processor = new FrameMottler();
        processor.initialise(infoVideo);

        List<byte[]> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Frame frame = createTestFrame(width, height);
            processor.process(frame, new InfoFrame(i, i * 41666L));
            expected.add(extractBytes(frame));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<byte[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int frameNum = i;
            tasks.add(() -> {
                Frame frame = createTestFrame(width, height);
                processor.process(frame, new InfoFrame(frameNum, frameNum * 41666L));
                return extractBytes(frame);
            });
        }

        List<Future<byte[]>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (int i = 0; i < 8; i++) {
            byte[] actual = futures.get(i).get();
            assertArrayEquals(expected.get(i), actual, "Mottler frame " + i + " must match sequential baseline");
        }
    }
}
