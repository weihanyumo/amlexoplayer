/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.source.chunk;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import android.util.Log;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.source.chunk.ChunkExtractor.TrackOutputProvider;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceUtil;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.util.ConditionVariable;
import com.google.android.exoplayer2.util.TraceUtil;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * A {@link BaseMediaChunk} that uses an {@link Extractor} to decode sample data.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public class ContainerMediaChunk extends BaseMediaChunk {
  private static String TAG = "ContainerMediaChunk";

  private final int chunkCount;
  private final long sampleOffsetUs;
  private final ChunkExtractor chunkExtractor;

  private long nextLoadPosition;
  private volatile boolean loadCanceled;
  private boolean loadCompleted;
  private ConditionVariable loadCondition = new ConditionVariable();

  /**
   * @param dataSource The source from which the data should be loaded.
   * @param dataSpec Defines the data to be loaded.
   * @param trackFormat See {@link #trackFormat}.
   * @param trackSelectionReason See {@link #trackSelectionReason}.
   * @param trackSelectionData See {@link #trackSelectionData}.
   * @param startTimeUs The start time of the media contained by the chunk, in microseconds.
   * @param endTimeUs The end time of the media contained by the chunk, in microseconds.
   * @param clippedStartTimeUs The time in the chunk from which output will begin, or {@link
   *     C#TIME_UNSET} to output from the start of the chunk.
   * @param clippedEndTimeUs The time in the chunk from which output will end, or {@link
   *     C#TIME_UNSET} to output to the end of the chunk.
   * @param chunkIndex The index of the chunk, or {@link C#INDEX_UNSET} if it is not known.
   * @param chunkCount The number of chunks in the underlying media that are spanned by this
   *     instance. Normally equal to one, but may be larger if multiple chunks as defined by the
   *     underlying media are being merged into a single load.
   * @param sampleOffsetUs An offset to add to the sample timestamps parsed by the extractor.
   * @param chunkExtractor A wrapped extractor to use for parsing the data.
   */
  public ContainerMediaChunk(
      DataSource dataSource,
      DataSpec dataSpec,
      Format trackFormat,
      @C.SelectionReason int trackSelectionReason,
      @Nullable Object trackSelectionData,
      long startTimeUs,
      long endTimeUs,
      long clippedStartTimeUs,
      long clippedEndTimeUs,
      long chunkIndex,
      int chunkCount,
      long sampleOffsetUs,
      ChunkExtractor chunkExtractor) {
    super(
        dataSource,
        dataSpec,
        trackFormat,
        trackSelectionReason,
        trackSelectionData,
        startTimeUs,
        endTimeUs,
        clippedStartTimeUs,
        clippedEndTimeUs,
        chunkIndex);
    Log.d(TAG, "ContainerMediaChunk chunkCount: "+ chunkCount);
    this.chunkCount = chunkCount;
    this.sampleOffsetUs = sampleOffsetUs;
    this.chunkExtractor = chunkExtractor;
  }

  @Override
  public long getNextChunkIndex() {
    return chunkIndex + chunkCount;
  }

  @Override
  public boolean isLoadCompleted() {
    return loadCompleted;
  }

  // Loadable implementation.

  @Override
  public final void cancelLoad() {
//    Log.d(TAG, "cancelLoad: ");
    loadCondition.open();
    loadCanceled = true;
  }

  @SuppressWarnings("NonAtomicVolatileUpdate")
  @Override
  public final void load() throws IOException {
    if (nextLoadPosition == 0) {
      // Configure the output and set it as the target for the extractor wrapper.
      BaseMediaChunkOutput output = getOutput();
      output.setSampleOffsetUs(sampleOffsetUs);
      chunkExtractor.init(
          getTrackOutputProvider(output),
          clippedStartTimeUs == C.TIME_UNSET ? C.TIME_UNSET : (clippedStartTimeUs - sampleOffsetUs),
          clippedEndTimeUs == C.TIME_UNSET ? C.TIME_UNSET : (clippedEndTimeUs - sampleOffsetUs));
    }
    try {
      // Create and open the input.
      DataSpec loadDataSpec = dataSpec.subrange(nextLoadPosition);
      Log.d(TAG, "load position: " + loadDataSpec.position);
      ExtractorInput input =
          new DefaultExtractorInput(
              dataSource, loadDataSpec.position, dataSource.open(loadDataSpec));
      // Load and decode the sample data.
      long position = input.getPosition();
      boolean result = true;
      loadCondition.open();
      try {
        while (result && !loadCanceled) {
          try {
            loadCondition.block();
          } catch (InterruptedException e) {
            throw new InterruptedIOException();
          }
          result = chunkExtractor.read(input);
          if (!result) {
            break;
          }
          long currentInputPosition = input.getPosition();
          if (currentInputPosition > position + 128 * 1024) {
            position = currentInputPosition;

            loadCondition.close();
            TraceUtil.beginSection("askContinue");
            if (!loadCanceled) {
              checkNotNull(callback).continueLoadingChunkRequested();
            }
            TraceUtil.endSection();
          }
          Thread.sleep(1);
        }
      } catch (InterruptedException e) {
          throw new RuntimeException(e);
      } finally {
        nextLoadPosition = input.getPosition() - dataSpec.position;
      }
    } finally {
      DataSourceUtil.closeQuietly(dataSource);
    }
    loadCompleted = !loadCanceled;
  }

  public boolean continueLoading(long playbackPositionUs) {
//    Log.d(TAG, "continueLoading: loadCondition open: "+ loadCondition);
    return loadCondition.open();
  }

  /**
   * Returns the {@link TrackOutputProvider} to be used by the wrapped extractor.
   *
   * @param baseMediaChunkOutput The {@link BaseMediaChunkOutput} most recently passed to {@link
   *     #init(BaseMediaChunkOutput)}.
   * @return A {@link TrackOutputProvider} to be used by the wrapped extractor.
   */
  protected TrackOutputProvider getTrackOutputProvider(BaseMediaChunkOutput baseMediaChunkOutput) {
    return baseMediaChunkOutput;
  }
}
