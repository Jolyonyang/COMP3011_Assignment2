/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. JiaoYang Yang (a3126438)
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.Buffer;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.bytedeco.javacv.Frame;

/**
 * Streams decoded audio from JavaCV frames to the Java Sound output device so
 * that the video player
 * can play the sound track as well. It's a very good test of a player because
 * providing sound again
 * (or not) is something that can be very easily distinguished by the ear
 * if/when playback is slow
 * and audio is disrupted.
 *
 * <p>
 * Converts supported packed or planar 16-bit sample buffers to the
 * interleaved, little-endian PCM format that is required by
 * {@link SourceDataLine}.
 * The {@link VideoPlayerModel} singleton is responsible for scheduling when the
 * audio being streamed
 * is written to the audio line / owns the AudioPlayer.
 * </p>
 */
public class AudioPlayer implements AutoCloseable {
    private static final long WRITE_TRACE_THRESHOLD_NS = 2_000_000L;
    private static final int AUDIO_WARMUP_BYTES = 4096;

    private SourceDataLine line; // Java Sound's audio-output interface - for us a destination, not a source.
    private byte[] outputBuffer = new byte[0];

    public void open(int sampleRate, int channels) throws LineUnavailableException {
        if (sampleRate <= 0 || channels <= 0) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        warmUpLine();
    }

    public byte[] copySamples(Frame frame) {
        if (frame.samples == null || frame.samples.length == 0) {
            return new byte[0];
        }

        int byteCount = copySamplesToOutputBuffer(frame);
        if (byteCount <= 0) {
            return new byte[0];
        }

        byte[] samples = new byte[byteCount];
        System.arraycopy(outputBuffer, 0, samples, 0, byteCount);
        return samples;
    }

    public int write(byte[] samples, int offset, int length) {
        if (line == null || samples.length == 0 || length <= 0) {
            return 0;
        }

        // Try and avoid blocking writes to the audio stream by only adding equal or
        // less than the number of bytes
        // available. We return only the bytes we were able to write without overflowing
        // the buffer, so the remaining
        // bytes can be tracked and written later. This avoids audio buffer blocking in
        // a cooperative multitasking
        // architecture - i.e. we are being cooperative and not stalling the thread on
        // line.write below!
        int availableBeforeWrite = line.available();
        int byteCount = Math.min(length, availableBeforeWrite);
        if (byteCount <= 0) {
            return 0;
        }

        long startNs = System.nanoTime();
        int written = line.write(samples, offset, byteCount); // Will block if we get it wrong.
        long elapsedNs = System.nanoTime() - startNs;
        if (elapsedNs >= WRITE_TRACE_THRESHOLD_NS) {
            // Hopefully you'll never see this.
            System.out.printf(
                    "WARNING: Audio write blocked for %dus writing %d bytes, line available before write: %d bytes%n",
                    elapsedNs / 1000,
                    written,
                    availableBeforeWrite);
        }
        return written;
    }

    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    // The motivation behind the implementation of this feature is due to the
    // presence of delays in the output of audio on certain audio output systems.
    // Specifically, some such systems impose the 'wake up' period before they
    // output audio. This can be troublesome when audio has to play in sync with
    // video frames, as the two would not be synchronized in real time. Therefore,
    // this method of producing audio entails writing a great deal of silence in the
    // audio system. The audio system is then flushed, which has the effect of
    // disposing of the 'wake up' period from the audio output.
    private void warmUpLine() {
        byte[] silence = new byte[Math.min(AUDIO_WARMUP_BYTES, line.getBufferSize())]; // All zeros.
        line.write(silence, 0, silence.length); // May play, but harmless.
        line.flush(); // Get rid of the rest straight away, hopefully warm up is done.
    }

    // This process is somewhat tedious but allows for the handling of interleaved
    // stereo audio (normal) and audio "packed" such that there is a block of left
    // speaker audio followed by a block of right speaker audio (which is unique and
    // requires a conversion).
    private int copySamplesToOutputBuffer(Frame frame) {
        Buffer[] samples = frame.samples;
        int channels = frame.audioChannels;

        if (channels > 1 && samples.length == channels && samples[0] instanceof ShortBuffer) {
            ShortBuffer[] planarSamples = new ShortBuffer[channels];
            int sampleCount = Integer.MAX_VALUE;

            for (int channel = 0; channel < channels; channel++) {
                if (!(samples[channel] instanceof ShortBuffer channelSamples)) {
                    return 0;
                }
                planarSamples[channel] = channelSamples.duplicate();
                sampleCount = Math.min(sampleCount, planarSamples[channel].remaining());
            }

            ensureOutputBufferCapacity(sampleCount * channels * Short.BYTES);
            return copyPlanarSamples(planarSamples, sampleCount);
        }

        if (samples[0] instanceof ShortBuffer packedSamples) {
            ShortBuffer source = packedSamples.duplicate();
            ensureOutputBufferCapacity(source.remaining() * Short.BYTES);
            return copyPackedSamples(source);
        }

        return 0;
    }

    private int copyPackedSamples(ShortBuffer source) {
        int outputIndex = 0;
        while (source.hasRemaining()) {
            short sample = source.get();
            outputBuffer[outputIndex++] = (byte) (sample & 0xff);
            outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
        }
        return outputIndex;
    }

    private int copyPlanarSamples(ShortBuffer[] planarSamples, int sampleCount) {
        int outputIndex = 0;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            for (ShortBuffer channelSamples : planarSamples) {
                short sample = channelSamples.get();
                outputBuffer[outputIndex++] = (byte) (sample & 0xff);
                outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
            }
        }
        return outputIndex;
    }

    private void ensureOutputBufferCapacity(int byteCount) {
        if (outputBuffer.length < byteCount) {
            outputBuffer = new byte[byteCount];
        }
    }

}
