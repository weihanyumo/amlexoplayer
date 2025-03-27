package com.google.android.exoplayer2.extractor;

import android.renderscript.ScriptGroup;
import android.util.Log;
import android.util.Pair;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.ParsableNalUnitBitArray;

import org.w3c.dom.NameList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;

public class ESExtractor implements Extractor, SeekMap {

    private static final int STATE_READING_ATOM_HEADER = 0;
    private static final int STATE_READING_ATOM_PAYLOAD = 1;
    private static final int STATE_READING_SAMPLE = 2;
    private static final int STATE_READING_SEF = 3;
    int extractorState = STATE_READING_ATOM_HEADER;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private static final int BUFFER_SIZE = 1024;
    private long pts = 0;
    private float FPS = 30;

    int writePos = 0;
    private ExtractorOutput output;
    private TrackOutput trackOutput;

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        ParsableByteArray header = new ParsableByteArray(4);
        input.peekFully(header.getData(), 0, 4);
        boolean is264 = isH264(header.getData());
        return is264;
    }
    private boolean isH264(byte[] header) {
        return header[0] == 0x00 && header[1] == 0x00 && header[2] == 0x00 && header[3] == 0x01;
    }
    @Override
    public void init(ExtractorOutput output) {
        pts = 0;
        this.output = output;
        this.trackOutput = output.track(0, C.TRACK_TYPE_VIDEO);
        output.seekMap(this);
        output.endTracks();
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        byte[] tempBuffer = new byte[BUFFER_SIZE];
        int bytesRead = input.read(tempBuffer, 0, tempBuffer.length);

        if (bytesRead == C.RESULT_END_OF_INPUT) {
            if (buffer.size() > 0) {
                processFrame(buffer.toByteArray());
                buffer.reset();
            }
            return RESULT_END_OF_INPUT;
        }
        buffer.write(tempBuffer, 0, bytesRead);

        if (extractorState == STATE_READING_ATOM_HEADER) {
            if (initializeFormatFromBuffer(buffer.toByteArray())) {
                extractorState = STATE_READING_ATOM_PAYLOAD;
            } else {
                Log.e("TAG", "read: "  );
                throw new InterruptedIOException();
            }
        } else {

            while (true) {
                byte[] frame = extractFrame(buffer.toByteArray());
                if (frame == null) {
                    break;
                }
                processFrame(frame);
            }
        }
        return RESULT_CONTINUE;

    }
    private boolean initializeFormatFromBuffer(byte[] data) {
        int spsStartIndex = findNalUnit(data, 0, 0x7);
        int ppsStartIndex = findNalUnit(data, 0, 0x8);

        if (spsStartIndex == -1 || ppsStartIndex == -1) {
            return false;
        }

        byte[] sps = extractNalUnit(data, spsStartIndex);
        byte[] pps = extractNalUnit(data, ppsStartIndex);

        Format format = parseSps(sps);
        trackOutput.format(format);
        return true;
    }
    private Format parseSps(byte[] spsData) {
        NalUnitUtil.SpsData sps = NalUnitUtil.parseSpsNalUnit(spsData, 4, spsData.length);
        FPS = sps.frameRate;
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(sps.width)
                .setHeight(sps.height)
                .setPixelWidthHeightRatio(sps.pixelWidthHeightRatio)
                .build();
    }

    private int findNalUnit(byte[] data, int startIndex,  int nalType) {
        for (int i = startIndex; i < data.length - 4; i++) {
            int type = data[i + 4] & 0x1F;
            int byte0 = data[i];
            int byte1 = data[i+1];
            int byte2 = data[i+2];
            int byte3 = data[i+3];

            if (byte0 == 0x00 && byte1 == 0x00 && byte2 == 0x00 && byte3 == 0x01) {
                if ( type == nalType || nalType == -1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private byte[] extractNalUnit(byte[] data, int startIndex) {
        int endIndex = findNalUnit(data, startIndex+1, -1);
        if (endIndex == -1) {
            endIndex = data.length;
        }
        return Arrays.copyOfRange(data, startIndex, endIndex);
    }

    private void processFrame(byte[] frame) {
        pts = extractTimestamp(frame);

        trackOutput.sampleData(new ParsableByteArray(frame), frame.length);
        trackOutput.sampleMetadata(
                pts,
                C.BUFFER_FLAG_KEY_FRAME,
                frame.length,
                0,
                null);
    }

    private long extractTimestamp(byte[] frame) {
        return (long) (pts + 1000000/FPS);
    }

    private byte[] extractFrame(byte[] data) {
        int frameEndIndex = findFrameEnd(1,data);
        if (frameEndIndex == -1) {
            return null;
        }

        byte[] frame = Arrays.copyOfRange(data, 0, frameEndIndex);
        buffer.reset();
        buffer.write(data, frameEndIndex, data.length - frameEndIndex);

        return frame;
    }

    private int findFrameEnd(int startPos, byte[] data) {
        for (int i = startPos; i < data.length - 3; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x00 && data[i+3] == 0x01) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void seek(long position, long timeUs) {

    }

    @Override
    public void release() {

    }



    @Override
    public boolean isSeekable() {
        return false;
    }

    @Override
    public long getDurationUs() {
        return 0;
    }

    @Override
    public SeekPoints getSeekPoints(long timeUs) {
        return null;
    }
}


