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

import static com.google.android.exoplayer2.mediacodec.SynchronousMediaCodecAdapter.deepCopyVisible;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.CryptoInfo;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.video.VideoDecoderGLSurfaceView;
import com.google.common.base.Supplier;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;

/**
 * A {@link MediaCodecAdapter} that operates the underlying {@link MediaCodec} in asynchronous mode,
 * routes {@link MediaCodec.Callback} callbacks on a dedicated thread that is managed internally,
 * and queues input buffers asynchronously.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@RequiresApi(23)
@Deprecated
/* package */ final class AsynchronousMediaCodecAdapter implements MediaCodecAdapter {
  public VideoDecoderGLSurfaceView glSurfaceView;

  /** A factory for {@link AsynchronousMediaCodecAdapter} instances. */
  public static final class Factory implements MediaCodecAdapter.Factory {
    private final Supplier<HandlerThread> callbackThreadSupplier;
    private final Supplier<HandlerThread> queueingThreadSupplier;
    private final boolean synchronizeCodecInteractionsWithQueueing;

    /**
     * Creates an factory for {@link AsynchronousMediaCodecAdapter} instances.
     *
     * @param trackType One of {@link C#TRACK_TYPE_AUDIO} or {@link C#TRACK_TYPE_VIDEO}. Used for
     *     labelling the internal thread accordingly.
     * @param synchronizeCodecInteractionsWithQueueing Whether the adapter should synchronize {@link
     *     MediaCodec} interactions with asynchronous buffer queueing. When {@code true}, codec
     *     interactions will wait until all input buffers pending queueing wil be submitted to the
     *     {@link MediaCodec}.
     */
    public Factory(@C.TrackType int trackType, boolean synchronizeCodecInteractionsWithQueueing) {
      this(
          /* callbackThreadSupplier= */ () ->
              new HandlerThread(createCallbackThreadLabel(trackType)),
          /* queueingThreadSupplier= */ () ->
              new HandlerThread(createQueueingThreadLabel(trackType)),
          synchronizeCodecInteractionsWithQueueing);
    }

    @VisibleForTesting
    /* package */ Factory(
        Supplier<HandlerThread> callbackThreadSupplier,
        Supplier<HandlerThread> queueingThreadSupplier,
        boolean synchronizeCodecInteractionsWithQueueing) {
      this.callbackThreadSupplier = callbackThreadSupplier;
      this.queueingThreadSupplier = queueingThreadSupplier;
      this.synchronizeCodecInteractionsWithQueueing = synchronizeCodecInteractionsWithQueueing;
    }

    @Override
    public AsynchronousMediaCodecAdapter createAdapter(Configuration configuration)
        throws IOException {
      Log.d("AsynchronousMediaCodec", "createAdapter: ");
      String codecName = configuration.codecInfo.name;
      @Nullable AsynchronousMediaCodecAdapter codecAdapter = null;
      @Nullable MediaCodec codec = null;
      try {
        TraceUtil.beginSection("createCodec:" + codecName);
        codec = MediaCodec.createByCodecName(codecName);
        codecAdapter =
            new AsynchronousMediaCodecAdapter(
                codec,
                callbackThreadSupplier.get(),
                queueingThreadSupplier.get(),
                synchronizeCodecInteractionsWithQueueing);
        TraceUtil.endSection();
        codecAdapter.initialize(
            configuration.mediaFormat,
            configuration.surface,
            configuration.crypto,
            configuration.flags);
        return codecAdapter;
      } catch (Exception e) {
        if (codecAdapter != null) {
          codecAdapter.release();
        } else if (codec != null) {
          codec.release();
        }
        throw e;
      }
    }
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_CREATED, STATE_INITIALIZED, STATE_SHUT_DOWN})
  private @interface State {}

  private static final int STATE_CREATED = 0;
  private static final int STATE_INITIALIZED = 1;
  private static final int STATE_SHUT_DOWN = 2;

  private final MediaCodec codec;
  private final AsynchronousMediaCodecCallback asynchronousMediaCodecCallback;
  private final AsynchronousMediaCodecBufferEnqueuer bufferEnqueuer;
  private final boolean synchronizeCodecInteractionsWithQueueing;
  private boolean codecReleased;
  private @State int state;

  private AsynchronousMediaCodecAdapter(
      MediaCodec codec,
      HandlerThread callbackThread,
      HandlerThread enqueueingThread,
      boolean synchronizeCodecInteractionsWithQueueing) {
    this.codec = codec;
    this.asynchronousMediaCodecCallback = new AsynchronousMediaCodecCallback(callbackThread);

    this.bufferEnqueuer = new AsynchronousMediaCodecBufferEnqueuer(codec, enqueueingThread);
    this.synchronizeCodecInteractionsWithQueueing = synchronizeCodecInteractionsWithQueueing;
    this.state = STATE_CREATED;
  }

  private void initialize(
      @Nullable MediaFormat mediaFormat,
      @Nullable Surface surface,
      @Nullable MediaCrypto crypto,
      int flags) {
    asynchronousMediaCodecCallback.initialize(codec);

    TraceUtil.beginSection("configureCodec");
    codec.configure(mediaFormat, surface, crypto, flags);
    TraceUtil.endSection();
    bufferEnqueuer.start();
    TraceUtil.beginSection("startCodec");
    codec.start();
    TraceUtil.endSection();
    state = STATE_INITIALIZED;
  }

  @Override
  public boolean needsReconfiguration() {
    return false;
  }

  @Override
  public void queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    bufferEnqueuer.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
  }

  @Override
  public void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
    bufferEnqueuer.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags);
  }

  @Override
  public void releaseOutputBuffer(int index, boolean render) {
    if(glSurfaceView != null ){
      if (render) {
//        VideoDecoderOutputBuffer videoOutputBuffer = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
//        videoOutputBuffer.index = index;
//        ByteBuffer buffer = codec.getOutputBuffer(index);
//        MediaFormat format = codec.getOutputFormat();
//        int width = format.getInteger(MediaFormat.KEY_WIDTH);
//        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
//        int yStride = format.getInteger(MediaFormat.KEY_STRIDE);
//        videoOutputBuffer.data = deepCopyVisible(buffer);
//        videoOutputBuffer.initForYuvFrame(width, height, yStride, yStride / 2, VideoDecoderOutputBuffer.COLORSPACE_BT709);
//        glSurfaceView.setOutputBuffer(videoOutputBuffer);
      }
      codec.releaseOutputBuffer(index, false);
    } else {
      codec.releaseOutputBuffer(index, render);
    }
  }

  @Override
  public void releaseOutputBuffer(int index, long renderTimeStampNs) {
    if(glSurfaceView != null){
//      VideoDecoderOutputBuffer videoOutputBuffer = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
//      videoOutputBuffer.index = index;
//      ByteBuffer buffer = codec.getOutputBuffer(index);
//      MediaFormat format = codec.getOutputFormat();
//      int width = format.getInteger(MediaFormat.KEY_WIDTH);
//      int height = format.getInteger(MediaFormat.KEY_HEIGHT);
//      int yStride = format.getInteger(MediaFormat.KEY_STRIDE);
//      videoOutputBuffer.data = deepCopyVisible(buffer);
//      videoOutputBuffer.initForYuvFrame(width, height,yStride,yStride/2,VideoDecoderOutputBuffer.COLORSPACE_BT709);
//      glSurfaceView.setOutputBuffer(videoOutputBuffer);
      Log.d("MediaCodecAdapter", "releaseOutputBuffer: "+index);
      codec.releaseOutputBuffer(index, false);

    } else {
      codec.releaseOutputBuffer(index, renderTimeStampNs);
    }
  }

  private void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    //nothing
  }

  @Override
  public int dequeueInputBufferIndex() {
    bufferEnqueuer.maybeThrowException();
    return asynchronousMediaCodecCallback.dequeueInputBufferIndex();
  }

  @Override
  public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
    bufferEnqueuer.maybeThrowException();
    return asynchronousMediaCodecCallback.dequeueOutputBufferIndex(bufferInfo);
  }

  @Override
  public MediaFormat getOutputFormat() {
    return asynchronousMediaCodecCallback.getOutputFormat();
  }

  @Override
  @Nullable
  public ByteBuffer getInputBuffer(int index) {
    return codec.getInputBuffer(index);
  }

  @Override
  @Nullable
  public ByteBuffer getOutputBuffer(int index) {
    return codec.getOutputBuffer(index);
  }

  @Override
  public void flush() {
    // The order of calls is important:
    // 1. Flush the bufferEnqueuer to stop queueing input buffers.
    // 2. Flush the codec to stop producing available input/output buffers.
    // 3. Flush the callback so that in-flight callbacks are discarded.
    // 4. Start the codec. The asynchronous callback will drop pending callbacks and we can start
    //    the codec now.
    bufferEnqueuer.flush();
    codec.flush();
    asynchronousMediaCodecCallback.flush();
    codec.start();
  }

  @Override
  public void release() {
    try {
      if (state == STATE_INITIALIZED) {
        bufferEnqueuer.shutdown();
        asynchronousMediaCodecCallback.shutdown();
      }
      state = STATE_SHUT_DOWN;
    } finally {
      if (!codecReleased) {
        codec.release();
        codecReleased = true;
      }
    }
  }

  @Override
  public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    maybeBlockOnQueueing();
    codec.setOnFrameRenderedListener(
        (codec, presentationTimeUs, nanoTime) ->
            listener.onFrameRendered(
                AsynchronousMediaCodecAdapter.this, presentationTimeUs, nanoTime),
        handler);
  }

  @Override
  public void setOutputSurface(Surface surface) {
    maybeBlockOnQueueing();
    codec.setOutputSurface(surface);
  }

  @Override

  public void setGlSurfaceView(VideoDecoderGLSurfaceView surfaceView){
    glSurfaceView = surfaceView;

    if (this.asynchronousMediaCodecCallback != null && glSurfaceView != null) {
      Log.d("MediaCodecAdapter", "initialize: glsurfaceView: " + glSurfaceView);
      this.asynchronousMediaCodecCallback.isGlView = true;
    }
  }

  @Override
  public void setParameters(Bundle params) {
    maybeBlockOnQueueing();
    codec.setParameters(params);
  }

  @Override
  public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
    maybeBlockOnQueueing();
    codec.setVideoScalingMode(scalingMode);
  }

  @Override
  @RequiresApi(26)
  public PersistableBundle getMetrics() {
    maybeBlockOnQueueing();
    return codec.getMetrics();
  }

  @VisibleForTesting
  /* package */ void onError(MediaCodec.CodecException error) {
    asynchronousMediaCodecCallback.onError(codec, error);
  }

  @VisibleForTesting
  /* package */ void onOutputFormatChanged(MediaFormat format) {
    asynchronousMediaCodecCallback.onOutputFormatChanged(codec, format);
  }

  private void maybeBlockOnQueueing() {
    if (synchronizeCodecInteractionsWithQueueing) {
      try {
        bufferEnqueuer.waitUntilQueueingComplete();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // The playback thread should not be interrupted. Raising this as an
        // IllegalStateException.
        throw new IllegalStateException(e);
      }
    }
  }

  private static String createCallbackThreadLabel(@C.TrackType int trackType) {
    return createThreadLabel(trackType, /* prefix= */ "ExoPlayer:MediaCodecAsyncAdapter:");
  }

  private static String createQueueingThreadLabel(@C.TrackType int trackType) {
    return createThreadLabel(trackType, /* prefix= */ "ExoPlayer:MediaCodecQueueingThread:");
  }

  private static String createThreadLabel(@C.TrackType int trackType, String prefix) {
    StringBuilder labelBuilder = new StringBuilder(prefix);
    if (trackType == C.TRACK_TYPE_AUDIO) {
      labelBuilder.append("Audio");
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      labelBuilder.append("Video");
    } else {
      labelBuilder.append("Unknown(").append(trackType).append(")");
    }
    return labelBuilder.toString();
  }
}
