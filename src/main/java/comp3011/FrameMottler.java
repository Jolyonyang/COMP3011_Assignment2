/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. COMP3011 Student (Assignment 2 Multi-Threaded Video Player)
 *
 * Attribution / Acknowledgments:
 *   - Refactored instance damage mask cache and block scale indices into ThreadLocal and
 *     local parameters to eliminate data races and critical section contention (CLO1).
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.ByteBuffer;
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Simulates patchy, uneven emulsion deterioration across each frame.
 *
 * <p>
 * Combines three multi-scale noise fields to perturb brightness and color
 * balance. High-pass damage masks modulate the effect so that deterioration
 * appears in blotchy clusters rather than uniform noise.
 * </p>
 */
public class FrameMottler extends FrameProcessor {
    private static final int NOISE_GRID_WIDTH = 120;
    private static final int NOISE_GRID_HEIGHT = 68;
    private static final int FINE_SMOOTHING_PASSES = 1;
    private static final int MEDIUM_SMOOTHING_PASSES = 3;
    private static final int LARGE_SMOOTHING_PASSES = 6;
    private static final int DAMAGE_MASK_SMOOTHING_PASSES = 4;
    private static final int DAMAGE_MASK_BLEND_FRAMES = 12;
    private static final double DAMAGE_MASK_THRESHOLD = 0.55;
    private static final int EFFECT_BLOCK_SIZE = 4;
    private static final int SCALE_LOOKUP_SIZE = 512;
    private static final double MAX_DARKEN_PERCENTAGE = 18.0;
    private static final double MAX_LIGHTEN_PERCENTAGE = 5.0;
    private static final double STAIN_THRESHOLD = 0.35;
    private static final double RED_STAIN_BOOST_PERCENTAGE = 6.0;
    private static final double GREEN_STAIN_BOOST_PERCENTAGE = 2.0;
    private static final double BLUE_STAIN_LOSS_PERCENTAGE = 12.0;
    private static final int SEED_SALT = 0x4d4f5454;
    private static final int BRIGHTNESS_FIELD_SALT = 0x42524947;
    private static final int STAIN_FIELD_SALT = 0x53544149;
    private static final int DAMAGE_MASK_SALT = 0x44414d47;
    private static final int FINE_FIELD_SALT = 0x46494e45;
    private static final int MEDIUM_FIELD_SALT = 0x4d454449;
    private static final int LARGE_FIELD_SALT = 0x4c415247;

    private static int nextInstanceIndex;

    private static final class DamageMaskCache {
        int segment = Integer.MIN_VALUE;
        double[] damageMaskA;
        double[] damageMaskB;
    }

    private final ThreadLocal<DamageMaskCache> threadLocalDamageMaskCache = ThreadLocal.withInitial(DamageMaskCache::new);

    private final int instanceIndex;
    private int mediaSeed;
    private int[] xGridLeft;
    private double[] xGridBlend;
    private int[] yGridTop;
    private double[] yGridBlend;
    private byte[][] scaleLookups;
    private double minimumScale;
    private double maximumScale;

    public FrameMottler() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex + SEED_SALT);
        buildSamplingLookups();
        buildScaleLookups();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameMottler requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameMottler requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameMottler requires at least three image channels");
        }

        double[] brightnessField = buildMultiScaleNoiseField(info.frameNumber(), BRIGHTNESS_FIELD_SALT);
        double[] stainField = buildMultiScaleNoiseField(info.frameNumber(), STAIN_FIELD_SALT);
        int damageMaskSegment = info.frameNumber() / DAMAGE_MASK_BLEND_FRAMES;
        double damageMaskBlend = (info.frameNumber() % DAMAGE_MASK_BLEND_FRAMES) / (double) DAMAGE_MASK_BLEND_FRAMES;

        DamageMaskCache cache = threadLocalDamageMaskCache.get();
        ensureDamageMaskFields(cache, damageMaskSegment);

        mottleFrame(pixels, brightnessField, stainField, cache, damageMaskBlend);
    }

    private void buildSamplingLookups() {
        xGridLeft = new int[videoInfo.imageWidth()];
        xGridBlend = new double[videoInfo.imageWidth()];
        yGridTop = new int[videoInfo.imageHeight()];
        yGridBlend = new double[videoInfo.imageHeight()];

        buildAxisSamplingLookups(videoInfo.imageWidth(), NOISE_GRID_WIDTH, xGridLeft, xGridBlend);
        buildAxisSamplingLookups(videoInfo.imageHeight(), NOISE_GRID_HEIGHT, yGridTop, yGridBlend);
    }

    private void buildAxisSamplingLookups(int imageSize, int gridSize, int[] gridStarts, double[] gridBlends) {
        if (imageSize <= 1 || gridSize <= 1) {
            for (int position = 0; position < imageSize; position++) {
                gridStarts[position] = 0;
                gridBlends[position] = 0.0;
            }
            return;
        }

        for (int position = 0; position < imageSize; position++) {
            double gridPosition = (position + 0.5) / imageSize * (gridSize - 1);
            gridPosition = clamp(gridPosition, 0.0, gridSize - 1.0);

            int gridStart = (int) Math.floor(gridPosition);
            if (gridStart >= gridSize - 1) {
                gridStart = gridSize - 2;
                gridPosition = gridSize - 1;
            }

            gridStarts[position] = gridStart;
            gridBlends[position] = gridPosition - gridStart;
        }
    }

    private void buildScaleLookups() {
        minimumScale = 1.0 - MAX_DARKEN_PERCENTAGE / 100.0 * (1.0 + BLUE_STAIN_LOSS_PERCENTAGE / 100.0);
        maximumScale = 1.0 + MAX_LIGHTEN_PERCENTAGE / 100.0 * (1.0 + RED_STAIN_BOOST_PERCENTAGE / 100.0);
        scaleLookups = new byte[SCALE_LOOKUP_SIZE][256];

        for (int i = 0; i < SCALE_LOOKUP_SIZE; i++) {
            double scale = minimumScale + (maximumScale - minimumScale) * i / (SCALE_LOOKUP_SIZE - 1);
            for (int value = 0; value < 256; value++) {
                scaleLookups[i][value] = (byte) clamp((int) Math.round(value * scale), 0, 255);
            }
        }
    }

    private void ensureDamageMaskFields(DamageMaskCache cache, int segment) {
        if (segment == cache.segment && cache.damageMaskA != null && cache.damageMaskB != null) {
            return;
        }

        cache.segment = segment;
        cache.damageMaskA = buildNoiseField(segment, DAMAGE_MASK_SALT, DAMAGE_MASK_SMOOTHING_PASSES);
        cache.damageMaskB = buildNoiseField(segment + 1, DAMAGE_MASK_SALT, DAMAGE_MASK_SMOOTHING_PASSES);
    }

    private double[] buildMultiScaleNoiseField(int frameNumber, int fieldSalt) {
        double[] fineField = buildNoiseField(frameNumber, fieldSalt ^ FINE_FIELD_SALT, FINE_SMOOTHING_PASSES);
        double[] mediumField = buildNoiseField(frameNumber, fieldSalt ^ MEDIUM_FIELD_SALT, MEDIUM_SMOOTHING_PASSES);
        double[] largeField = buildNoiseField(frameNumber, fieldSalt ^ LARGE_FIELD_SALT, LARGE_SMOOTHING_PASSES);

        double[] blended = new double[fineField.length];
        for (int i = 0; i < blended.length; i++) {
            blended[i] = fineField[i] * 0.20 + mediumField[i] * 0.35 + largeField[i] * 0.45;
        }
        return blended;
    }

    private double[] buildNoiseField(int frameNumber, int fieldSalt, int smoothingPasses) {
        double[] field = new double[NOISE_GRID_WIDTH * NOISE_GRID_HEIGHT];
        Random random = new Random(seedForField(frameNumber, fieldSalt));

        for (int i = 0; i < field.length; i++) {
            field[i] = random.nextDouble();
        }

        double[] scratch = new double[field.length];
        for (int pass = 0; pass < smoothingPasses; pass++) {
            smoothField(field, scratch);
            double[] temp = field;
            field = scratch;
            scratch = temp;
        }

        return field;
    }

    private long seedForField(int frameNumber, int fieldSalt) {
        long seed = Integer.toUnsignedLong(mediaSeed)
                ^ (Integer.toUnsignedLong(frameNumber) * 0x9e3779b97f4a7c15L)
                ^ Integer.toUnsignedLong(fieldSalt);

        seed ^= seed >>> 30;
        seed *= 0xbf58476d1ce4e5b9L;
        seed ^= seed >>> 27;
        seed *= 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        return seed;
    }

    private void smoothField(double[] source, double[] destination) {
        for (int y = 0; y < NOISE_GRID_HEIGHT; y++) {
            int yAbove = Math.max(0, y - 1);
            int yBelow = Math.min(NOISE_GRID_HEIGHT - 1, y + 1);

            int rowStart = y * NOISE_GRID_WIDTH;
            int aboveStart = yAbove * NOISE_GRID_WIDTH;
            int belowStart = yBelow * NOISE_GRID_WIDTH;

            for (int x = 0; x < NOISE_GRID_WIDTH; x++) {
                int xLeft = Math.max(0, x - 1);
                int xRight = Math.min(NOISE_GRID_WIDTH - 1, x + 1);

                double total = source[aboveStart + xLeft]
                        + source[aboveStart + x] * 2.0
                        + source[aboveStart + xRight]
                        + source[rowStart + xLeft] * 2.0
                        + source[rowStart + x] * 4.0
                        + source[rowStart + xRight] * 2.0
                        + source[belowStart + xLeft]
                        + source[belowStart + x] * 2.0
                        + source[belowStart + xRight];

                destination[rowStart + x] = total / 16.0;
            }
        }
    }

    private void mottleFrame(
            ByteBuffer pixels,
            double[] brightnessField,
            double[] stainField,
            DamageMaskCache cache,
            double damageMaskBlend) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        int[] scaleIndices = new int[3];

        for (int blockY = 0; blockY < height; blockY += EFFECT_BLOCK_SIZE) {
            int blockYEnd = Math.min(blockY + EFFECT_BLOCK_SIZE, height);
            int sampleY = (blockY + blockYEnd - 1) / 2;

            for (int blockX = 0; blockX < width; blockX += EFFECT_BLOCK_SIZE) {
                int blockXEnd = Math.min(blockX + EFFECT_BLOCK_SIZE, width);
                int sampleX = (blockX + blockXEnd - 1) / 2;

                if (!prepareScaleIndicesForBlock(
                        sampleX,
                        sampleY,
                        brightnessField,
                        stainField,
                        cache,
                        damageMaskBlend,
                        scaleIndices)) {
                    continue;
                }

                applyMottleBlock(
                        pixels,
                        blockX,
                        blockXEnd,
                        blockY,
                        blockYEnd,
                        channels,
                        stride,
                        scaleIndices[0],
                        scaleIndices[1],
                        scaleIndices[2]);
            }
        }
    }

    private boolean prepareScaleIndicesForBlock(
            int x,
            int y,
            double[] brightnessField,
            double[] stainField,
            DamageMaskCache cache,
            double damageMaskBlend,
            int[] outIndices) {
        double damageStrength = sampleDamageMask(x, y, cache, damageMaskBlend);
        if (damageStrength <= 0.0) {
            return false;
        }

        double brightness = smoothstep(sampleField(brightnessField, x, y));
        double stain = smoothstep(sampleField(stainField, x, y));

        setBlockScaleIndices(brightness, stain, damageStrength, outIndices);
        return true;
    }

    private double sampleDamageMask(int x, int y, DamageMaskCache cache, double damageMaskBlend) {
        double maskA = sampleField(cache.damageMaskA, x, y);
        double maskB = sampleField(cache.damageMaskB, x, y);
        double mask = smoothstep(lerp(maskA, maskB, damageMaskBlend));
        return smoothstep((mask - DAMAGE_MASK_THRESHOLD) / (1.0 - DAMAGE_MASK_THRESHOLD));
    }

    private double sampleField(double[] field, int x, int y) {
        int x0 = xGridLeft[x];
        int x1 = Math.min(x0 + 1, NOISE_GRID_WIDTH - 1);
        double xBlend = xGridBlend[x];

        int y0 = yGridTop[y];
        int y1 = Math.min(y0 + 1, NOISE_GRID_HEIGHT - 1);
        double yBlend = yGridBlend[y];

        double top = lerp(field[y0 * NOISE_GRID_WIDTH + x0], field[y0 * NOISE_GRID_WIDTH + x1], xBlend);
        double bottom = lerp(field[y1 * NOISE_GRID_WIDTH + x0], field[y1 * NOISE_GRID_WIDTH + x1], xBlend);
        return lerp(top, bottom, yBlend);
    }

    private void setBlockScaleIndices(double brightness, double stain, double damageStrength, int[] outIndices) {
        double brightnessScale;
        if (brightness < 0.5) {
            brightnessScale = 1.0 - (0.5 - brightness) * 2.0 * MAX_DARKEN_PERCENTAGE / 100.0;
        } else {
            brightnessScale = 1.0 + (brightness - 0.5) * 2.0 * MAX_LIGHTEN_PERCENTAGE / 100.0;
        }
        brightnessScale = 1.0 + (brightnessScale - 1.0) * damageStrength;

        double stainStrength = Math.max(0.0, (stain - STAIN_THRESHOLD) / (1.0 - STAIN_THRESHOLD));
        stainStrength = smoothstep(stainStrength) * damageStrength;

        double blueScale = brightnessScale * (1.0 - stainStrength * BLUE_STAIN_LOSS_PERCENTAGE / 100.0);
        double greenScale = brightnessScale * (1.0 + stainStrength * GREEN_STAIN_BOOST_PERCENTAGE / 100.0);
        double redScale = brightnessScale * (1.0 + stainStrength * RED_STAIN_BOOST_PERCENTAGE / 100.0);

        outIndices[0] = scaleIndex(blueScale);
        outIndices[1] = scaleIndex(greenScale);
        outIndices[2] = scaleIndex(redScale);
    }

    private void applyMottleBlock(
            ByteBuffer pixels,
            int blockX,
            int blockXEnd,
            int blockY,
            int blockYEnd,
            int channels,
            int stride,
            int blueIndex,
            int greenIndex,
            int redIndex) {
        byte[] blueLookup = scaleLookups[blueIndex];
        byte[] greenLookup = scaleLookups[greenIndex];
        byte[] redLookup = scaleLookups[redIndex];

        for (int y = blockY; y < blockYEnd; y++) {
            int rowStart = y * stride;
            for (int x = blockX; x < blockXEnd; x++) {
                int base = rowStart + x * channels;

                // JavaCV supplies these decoded frames in BGR byte order.
                pixels.put(base, blueLookup[Byte.toUnsignedInt(pixels.get(base))]);
                pixels.put(base + 1, greenLookup[Byte.toUnsignedInt(pixels.get(base + 1))]);
                pixels.put(base + 2, redLookup[Byte.toUnsignedInt(pixels.get(base + 2))]);
            }
        }
    }

    private int scaleIndex(double scale) {
        double blend = (scale - minimumScale) / (maximumScale - minimumScale);
        return clamp((int) Math.round(blend * (SCALE_LOOKUP_SIZE - 1)), 0, SCALE_LOOKUP_SIZE - 1);
    }

    private double lerp(double a, double b, double blend) {
        return a + (b - a) * blend;
    }

    private double smoothstep(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value * value * (3.0 - 2.0 * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
