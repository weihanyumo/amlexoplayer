/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.exoplayer2.mediacodec;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Util.castNonNull;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.CryptoInfo;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoDecoderGLSurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link MediaCodecAdapter} that operates the underlying {@link MediaCodec} in synchronous mode.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class SynchronousMediaCodecAdapter implements MediaCodecAdapter {
  public VideoDecoderGLSurfaceView glSurfaceView;
  /** A factory for {@link SynchronousMediaCodecAdapter} instances. */
  public static class Factory implements MediaCodecAdapter.Factory {

    @Override
    public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
      @Nullable MediaCodec codec = null;
      try {
        codec = createCodec(configuration);
        TraceUtil.beginSection("configureCodec");
        codec.configure(
            configuration.mediaFormat,
            configuration.surface,
            configuration.crypto,
            configuration.flags);
        TraceUtil.endSection();
        TraceUtil.beginSection("startCodec");
        codec.start();
        TraceUtil.endSection();
        return new SynchronousMediaCodecAdapter(codec);
      } catch (IOException | RuntimeException e) {
        if (codec != null) {
          codec.release();
        }
        throw e;
      }
    }

    /** Creates a new {@link MediaCodec} instance. */
    protected MediaCodec createCodec(Configuration configuration) throws IOException {
      checkNotNull(configuration.codecInfo);
      String codecName = configuration.codecInfo.name;
      TraceUtil.beginSection("createCodec:" + codecName);
      MediaCodec mediaCodec = MediaCodec.createByCodecName(codecName);
      TraceUtil.endSection();
      return mediaCodec;
    }
  }

  private final MediaCodec codec;
  @Nullable private ByteBuffer[] inputByteBuffers;
  @Nullable private ByteBuffer[] outputByteBuffers;

  private SynchronousMediaCodecAdapter(MediaCodec mediaCodec) {
    this.codec = mediaCodec;
    if (Util.SDK_INT < 21) {
      inputByteBuffers = codec.getInputBuffers();
      outputByteBuffers = codec.getOutputBuffers();
    }
  }

  @Override
  public boolean needsReconfiguration() {
    return false;
  }

  @Override
  public int dequeueInputBufferIndex() {
    return codec.dequeueInputBuffer(0);
  }

  @Override
  public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
    int index;
    do {
      index = codec.dequeueOutputBuffer(bufferInfo, 0);
      if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED && Util.SDK_INT < 21) {
        outputByteBuffers = codec.getOutputBuffers();
      }
    } while (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED);

    return index;
  }

  @Override
  public MediaFormat getOutputFormat() {
    return codec.getOutputFormat();
  }

  @Override
  @Nullable
  public ByteBuffer getInputBuffer(int index) {
    if (Util.SDK_INT >= 21) {
      return codec.getInputBuffer(index);
    } else {
      return castNonNull(inputByteBuffers)[index];
    }
  }

  @Override
  @Nullable
  public ByteBuffer getOutputBuffer(int index) {
    if (Util.SDK_INT >= 21) {
      return codec.getOutputBuffer(index);
    } else {
      return castNonNull(outputByteBuffers)[index];
    }
  }

  @Override
  public void queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
  }

  @Override
  public void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
    codec.queueSecureInputBuffer(
        index, offset, info.getFrameworkCryptoInfo(), presentationTimeUs, flags);
  }

  @Override
  public void releaseOutputBuffer(int index, boolean render) {
    codec.releaseOutputBuffer(index, render);
  }

  @Override
  @RequiresApi(21)
  public void releaseOutputBuffer(int index, long renderTimeStampNs) {
    if(glSurfaceView != null){
      VideoDecoderOutputBuffer videoOutputBuffer = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
      videoOutputBuffer.index = index;
      ByteBuffer buffer = codec.getOutputBuffer(index);
      MediaFormat format = codec.getOutputFormat();
      int width = format.getInteger(MediaFormat.KEY_WIDTH);
      int height = format.getInteger(MediaFormat.KEY_HEIGHT);
      int yStride = format.getInteger(MediaFormat.KEY_STRIDE);
      videoOutputBuffer.data = deepCopyVisible(buffer);
      videoOutputBuffer.initForYuvFrame(width, height,yStride,yStride/2,VideoDecoderOutputBuffer.COLORSPACE_BT709);
//      glSurfaceView.setOutputBuffer(videoOutputBuffer);
      codec.releaseOutputBuffer(index, false);
    }else {
      MediaFormat format = codec.getOutputFormat();
      int width = format.getInteger(MediaFormat.KEY_WIDTH);
      int height = format.getInteger(MediaFormat.KEY_HEIGHT);
      codec.releaseOutputBuffer(index, renderTimeStampNs);
    }
  }
  static public ByteBuffer deepCopyVisible( ByteBuffer orig ) {
    int pos = orig.position();
    try
    {
      ByteBuffer toReturn;
      // try to maintain implementation to keep performance
      if( orig.isDirect() )
        toReturn = ByteBuffer.allocateDirect(orig.remaining());
      else
        toReturn = ByteBuffer.allocate(orig.remaining());
      toReturn.put(orig);
      toReturn.order(orig.order());
      return (ByteBuffer) toReturn.position(0);
    }
    finally
    {
      orig.position(pos);
    }
  }

  public void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer){
    //codec.releaseOutputBuffer(outputBuffer.index, false);
  }
  @Override
  public void flush() {
    codec.flush();
  }

  @Override
  public void release() {
    inputByteBuffers = null;
    outputByteBuffers = null;
    codec.release();
  }

  @Override
  @RequiresApi(23)
  public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    codec.setOnFrameRenderedListener(
        (codec, presentationTimeUs, nanoTime) ->
            listener.onFrameRendered(
                SynchronousMediaCodecAdapter.this, presentationTimeUs, nanoTime),
        handler);
  }

  @Override
  @RequiresApi(23)
  public void setOutputSurface(Surface surface) {
    if(glSurfaceView != null){
      return;
    }
    codec.setOutputSurface(surface);
  }


  public void setGlSurfaceView(VideoDecoderGLSurfaceView surfaceView){
    glSurfaceView = surfaceView;
  }
  @Override
  @RequiresApi(19)
  public void setParameters(Bundle params) {
    codec.setParameters(params);
  }

  @Override
  public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
    codec.setVideoScalingMode(scalingMode);
  }

  @Override
  @RequiresApi(26)
  public PersistableBundle getMetrics() {
    return codec.getMetrics();
  }
}
