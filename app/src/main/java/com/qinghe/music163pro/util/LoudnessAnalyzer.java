package com.qinghe.music163pro.util;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * F5 限音器：单文件响度分析（离线）。
 *
 * <p><b>为什么有界解码</b>：目标是「同一批歌之间响度可比」，不需要逐样本精确到整首 ——
 * 只解前 {@link #MAX_ANALYZE_US} 秒（默认 10s）就足以估出 RMS 与峰值；
 * 500 首全解码会很久，而本功能必须能在后台跑、可中断（文档 F5 改动要点 1/4）。</p>
 *
 * <p><b>失败即跳过</b>：任何一步出问题（没有音轨、解码器建不起来、读到 0 个样本……）
 * 都返回 {@code null}，由调用方记日志跳过 —— 一个坏文件绝不能中断整批分析。</p>
 *
 * <p>输出：{@code float[]{rmsDbFS, peakDbFS}}（0 dBFS = 满刻度）。</p>
 */
public final class LoudnessAnalyzer {

    private static final String TAG = "Loudness";

    /** 只解前 10 秒。 */
    private static final long MAX_ANALYZE_US = 10_000_000L;
    /** 输出缓冲上限，防止个别文件把循环拖住。 */
    private static final int MAX_OUTPUT_BUFFERS = 400;
    /** 16bit 满刻度。 */
    private static final double FULL_SCALE = 32768.0;

    private LoudnessAnalyzer() {
    }

    /** @return {rmsDbFS, peakDbFS}；无法分析时返回 null。 */
    public static float[] analyze(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null || format.getString(MediaFormat.KEY_MIME) == null) {
                return null;
            }
            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            double sumSquares = 0;
            long sampleCount = 0;
            float peakAbs = 0f;
            int buffers = 0;

            while (!outputDone && buffers < MAX_OUTPUT_BUFFERS) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inIndex);
                        int size = input == null ? -1 : extractor.readSampleData(input, 0);
                        long timeUs = extractor.getSampleTime();
                        if (size < 0 || timeUs > MAX_ANALYZE_US) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, timeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out != null && out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    continue;
                }
                if (outIndex < 0) {
                    continue;
                }
                if (info.size > 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            while (fb.hasRemaining()) {
                                float v = fb.get();
                                sumSquares += (double) v * v;
                                sampleCount++;
                                float a = Math.abs(v);
                                if (a > peakAbs) {
                                    peakAbs = a;
                                }
                            }
                        } else {
                            ShortBuffer sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            while (sb.hasRemaining()) {
                                short s = sb.get();
                                sumSquares += (double) s * s;
                                sampleCount++;
                                int a = s == Short.MIN_VALUE ? 32768 : Math.abs(s);
                                if (a > peakAbs) {
                                    peakAbs = a;
                                }
                            }
                        }
                    }
                    buffers++;
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }

            if (sampleCount == 0) {
                return null;
            }
            double scale = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 1.0 : FULL_SCALE;
            double rms = Math.sqrt(sumSquares / sampleCount) / scale;
            double peak = peakAbs / scale;
            float rmsDb = (float) (20 * Math.log10(Math.max(rms, 1e-6)));
            float peakDb = (float) (20 * Math.log10(Math.max(peak, 1e-6)));
            return new float[]{rmsDb, peakDb};
        } catch (Throwable t) {
            MusicLog.w(TAG, "分析失败（跳过该文件）: " + path + " -> " + t);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
