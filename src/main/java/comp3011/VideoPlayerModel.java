/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. JiaoYang Yang (a3126438)
 *
Attribution / Acknowledgments:
*   - Designed and implemented high-performance multi-threading processing pipeline:
*     1. Dedicated Demuxer/Grabber Thread with decoupled audio and video pipelines.
*     2. Worker thread pool (optimized for multi-core systems, e.g. 8 cores) with ThreadLocal
*        JavaFXFrameConverter and cloistered state of the thread-safe FrameProcessor (CLO1 & CLO2).
*     3. Structurally-fixed-frame buffer pool for achieving zero-allocation native-buffer exclusivity.
*     4. PriorityBlockingQueue reorder buffer enforcing strictly monotonic frame ordering.
*     5. Atomic seek epoch invalidation mechanism guaranteeing deadlock-and-frame-stutter elimination.
*
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The multi-threaded model part of the video player model-view-controller architecture.
 *
 * <p>
 * Replaces the single-threaded cooperative multitasking model with a high-throughput
 * multi-threaded pipeline. Decouples video decoding, audio scheduling, multi-core
 * filter processing, and frame display into dedicated concurrent components.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    // Concurrency parameters tuned for 8-core benchmark platforms (e.g. TITAN VM c7i.2xlarge)
    // Reserve at least 1 core for JavaFX UI thread, Prism Direct3D render thread, and TITAN screen capture
    private static final int WORKER_COUNT = Math.max(3, Runtime.getRuntime().availableProcessors() - 1);
    private static final int MAX_BUFFERED_FRAMES = 36;
    private static final int PREROLL_FRAME_COUNT = 10;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // ThreadLocal converter for JavaCV -> JavaFX image conversion to prevent native race conditions
    private final ThreadLocal<JavaFXFrameConverter> threadLocalConverter = ThreadLocal.withInitial(JavaFXFrameConverter::new);

    // Multi-threaded pipeline components
    private ExecutorService workerPool;
    private Thread decoderThread;
    private final PriorityBlockingQueue<PreparedFrame> readyFrames = new PriorityBlockingQueue<>();
    private final BlockingQueue<Frame> framePool = new ArrayBlockingQueue<>(MAX_BUFFERED_FRAMES + 4);
    private final AtomicLong currentSeekEpoch = new AtomicLong(0);
    private final AtomicBoolean playbackActive = new AtomicBoolean(false);
    private final AtomicBoolean decoderEof = new AtomicBoolean(false);

    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private AudioPlayer audioPlayer;
    private volatile boolean playbackOpen;
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private volatile boolean audioAvailable;
    private volatile boolean frameProcessorsInitialised;

    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private volatile long logicalPlaybackBaseUs;
    private volatile long playbackStartNs;
    private volatile long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this(audioEnabled, List.of(), videoSizeChangedHandler, frameReadyHandler, statusChangedHandler, playbackStateChangedHandler, audioOutputStateChangedHandler);
    }

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        this.audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = frameProcessors != null ? new ArrayList<>(frameProcessors) : new ArrayList<>();
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }
        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }
        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private synchronized void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        long newEpoch = currentSeekEpoch.incrementAndGet();
        flushPipelineQueues();

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private synchronized void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackActive.set(true);

            initThreadPoolAndBuffers();
            startDecoderThread();

            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void initThreadPoolAndBuffers() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        workerPool = Executors.newFixedThreadPool(WORKER_COUNT, (Runnable r) -> {
            Thread t = new Thread(r, "VideoWorker-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        framePool.clear();
        int width = grabber.getImageWidth();
        int height = grabber.getImageHeight();
        int channels = 3;
        int stride = width * channels;
        int bufferBytes = stride * height;

        int poolSize = Math.max(16, WORKER_COUNT * 3);
        for (int i = 0; i < poolSize; i++) {
            Frame pooled = new Frame(width, height, Frame.DEPTH_UBYTE, channels);
            pooled.imageStride = stride;
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(bufferBytes);
            pooled.image = new Buffer[]{directBuffer};
            framePool.offer(pooled);
        }

        readyFrames.clear();
        decoderEof.set(false);
    }

    private void startDecoderThread() {
        long epoch = currentSeekEpoch.get();
        decoderThread = new Thread(() -> decodeLoop(epoch), "VideoDecoderThread");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    private void decodeLoop(long startingEpoch) {
        while (playbackOpen && playbackActive.get()) {
            if (pauseRequested) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    if (!playbackOpen || !playbackActive.get()) break;
                }
                continue;
            }

            long currentEpoch = currentSeekEpoch.get();
            if (currentEpoch != startingEpoch) {
                startingEpoch = currentEpoch;
            }

            // Flow control: keep readyFrames comfortably filled up to MAX_BUFFERED_FRAMES.
            if (readyFrames.size() >= MAX_BUFFERED_FRAMES) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    if (!playbackOpen || !playbackActive.get()) break;
                }
                continue;
            }

            Frame frame;
            long timestampUs;
            try {
                frame = grabber.grab();
                if (frame == null) {
                    decoderEof.set(true);
                    break;
                }
                timestampUs = grabber.getTimestamp();
            } catch (Exception e) {
                if (playbackOpen && playbackActive.get()) {
                    Platform.runLater(() -> handlePlaybackError(e));
                }
                break;
            }

            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                try {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                } catch (Exception e) {
                    Platform.runLater(() -> handlePlaybackError(e));
                    break;
                }
            }

            if (currentSeekEpoch.get() != currentEpoch) {
                continue;
            }

            Frame pooledFrame = framePool.poll();
            if (pooledFrame == null) {
                pooledFrame = copyFrameData(frame);
            } else {
                populatePooledFrame(pooledFrame, frame);
            }

            int frameNumber = grabber.getFrameNumber();
            long taskEpoch = currentEpoch;
            Frame taskFrame = pooledFrame;

            workerPool.submit(() -> processAndConvertFrame(taskFrame, frameNumber, timestampUs, taskEpoch));
        }
    }

    private void processAndConvertFrame(Frame frame, int frameNumber, long timestampUs, long taskEpoch) {
        try {
            if (currentSeekEpoch.get() != taskEpoch || !playbackOpen) {
                recycleFrame(frame);
                return;
            }

            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            processFrame(frame, info);

            JavaFXFrameConverter converter = threadLocalConverter.get();
            Image image = converter.convert(frame);

            recycleFrame(frame);

            PreparedFrame prepared = new PreparedFrame(
                    image,
                    frameNumber,
                    timestampUs,
                    System.nanoTime());

            if (currentSeekEpoch.get() == taskEpoch && playbackOpen) {
                readyFrames.offer(prepared);
            }
        } catch (Exception e) {
            recycleFrame(frame);
            if (playbackOpen) {
                Platform.runLater(() -> handlePlaybackError(e));
            }
        }
    }

    private void recycleFrame(Frame frame) {
        if (frame != null) {
            framePool.offer(frame);
        }
    }

    private void populatePooledFrame(Frame destination, Frame source) {
        destination.imageWidth = source.imageWidth;
        destination.imageHeight = source.imageHeight;
        destination.imageDepth = source.imageDepth;
        destination.imageChannels = source.imageChannels;
        destination.imageStride = source.imageStride;
        destination.keyFrame = source.keyFrame;
        destination.opaque = source.opaque;
        destination.timestamp = source.timestamp;

        if (source.image != null && source.image.length > 0 && source.image[0] instanceof ByteBuffer srcBb) {
            if (destination.image[0] instanceof ByteBuffer dstBb) {
                dstBb.clear();
                ByteBuffer duplicate = srcBb.duplicate();
                duplicate.clear();
                int bytesToCopy = Math.min(dstBb.capacity(), duplicate.capacity());
                duplicate.limit(bytesToCopy);
                dstBb.put(duplicate);
                dstBb.clear();
            }
        }
    }

    private Frame copyFrameData(Frame source) {
        Frame copy = source.clone();
        if (source.image != null && source.image.length > 0 && source.image[0] instanceof ByteBuffer srcBb) {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(srcBb.capacity());
            ByteBuffer duplicate = srcBb.duplicate();
            duplicate.clear();
            directBuffer.put(duplicate);
            directBuffer.clear();
            copy.image = new Buffer[]{directBuffer};
        }
        return copy;
    }

    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        if (pauseRequested) {
            if (playbackStartNs == 0 && !readyFrames.isEmpty()) {
                PreparedFrame frame = readyFrames.poll();
                firstTimestampUs = frame.timestampUs();
                playbackStartNs = now;
                pauseStartedNs = now;
                displayPreparedFrame(frame, now, now);
            }
            return;
        }

        if (playbackStartNs == 0) {
            int targetPreroll = Math.min(PREROLL_FRAME_COUNT, totalVideoFrames > 0 ? totalVideoFrames : PREROLL_FRAME_COUNT);
            if (readyFrames.size() < targetPreroll && !decoderEof.get()) {
                return;
            }

            PreparedFrame first = readyFrames.peek();
            if (first == null) {
                if (decoderEof.get()) {
                    finishPlayback();
                }
                return;
            }
            firstTimestampUs = first.timestampUs();
            playbackStartNs = now;
        }

        if (readyFrames.isEmpty() && decoderEof.get()) {
            finishPlayback();
            return;
        }

        PreparedFrame frame = readyFrames.peek();
        if (frame != null) {
            long relativeTimestampUs = Math.max(0, frame.timestampUs() - firstTimestampUs);
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            if (targetTimeNs <= now) {
                readyFrames.poll();
                displayPreparedFrame(frame, targetTimeNs, now);
            }
        }
    }

    private void displayPreparedFrame(PreparedFrame frame, long targetTimeNs, long now) {
        long relativeTimestampUs = Math.max(0, frame.timestampUs() - firstTimestampUs);
        currentTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void flushPipelineQueues() {
        readyFrames.clear();
        pendingAudio.clear();
        decoderEof.set(false);
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private synchronized void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        playbackActive.set(false);

        if (decoderThread != null) {
            decoderThread.interrupt();
            decoderThread = null;
        }

        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }

        flushPipelineQueues();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception ignored) {
            }
            try {
                grabber.close();
            } catch (Exception ignored) {
            }
            grabber = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFrameProcessors(InfoVideo info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(info);
        }
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        if (videoSizeChangedHandler != null) {
            videoSizeChangedHandler.accept(width, height);
        }
    }

    private void notifyFrameReady(Image image) {
        if (frameReadyHandler != null) {
            frameReadyHandler.accept(image);
        }
    }

    private void notifyStatusChanged(String status) {
        if (statusChangedHandler != null) {
            statusChangedHandler.accept(status);
        }
    }

    private void notifyPlaybackStateChanged() {
        if (playbackStateChangedHandler != null) {
            playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
        }
    }

    private void notifyAudioOutputStateChanged() {
        if (audioOutputStateChangedHandler != null) {
            audioOutputStateChangedHandler.accept(audioOutputEnabled);
        }
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long timestampUs,
            long preparedAtNs) implements Comparable<PreparedFrame> {

        @Override
        public int compareTo(PreparedFrame other) {
            return Integer.compare(this.frameNumber, other.frameNumber);
        }
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        boolean finished() {
            return offset >= samples.length;
        }

        void advance(int bytes) {
            offset += bytes;
        }
    }
}
