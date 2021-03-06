package com.google.android.exoplayer2.ext.mediaplayer;

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
 *
 * @author michalliu@tencent.com
 */

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.ext.audio.AudioFrameManager;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.ClippingMediaSource;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.DynamicConcatenatingMediaSource;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.UnrecognizedInputFormatException;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

//import com.google.android.exoplayer2.source.dash.DashMediaSource;
//import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
//import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
//import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;

//import com.google.android.exoplayer2.source.dash.DashMediaSource;
//import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
//import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
//import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;

@TargetApi(16)
public class ExoMediaPlayer implements MediaPlayerInterface, AudioLevelSupport {
    private static final String TAG = "ExoMediaPlayer";
    private static final String HANDLER_THREAD_NAME = "SimpleExoMediaPlayer_HandlerThread";
    private static final int BUFFER_REPEAT_DELAY = 1000;

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private static ILogger sLogger = null;

    private Context mAppContext;
    private ExoPlayer mExoPlayer;

    private List<Renderer> mRenderers;

    private HandlerThread mHandlerThread;

    private Repeater mBufferUpdateRepeater;

    private StateStore mStateStore;

    private MediaSource mMediaSource;

    private final AtomicBoolean mStopped = new AtomicBoolean();

    private Surface mSurface;
    private TextureView mTextureView;
    private boolean mOwnsSurface;

    private final SurfaceListener mSurfaceListener;

    private int mVideoWidth;
    private int mVideoHeight;

    private int mAudioSessionId = C.AUDIO_SESSION_ID_UNSET;

    private PowerManager.WakeLock mWakeLock = null;

    private ExoPlayer.EventListener mExo2EventListener;
    private MediaSourceEventListener mMediaSourceEventListener;

    private Handler mMainHandler;

    private boolean mStayAwake;
    private SurfaceHolder mSurfaceHolder;

    private boolean mScreenOnWhilePlaying;
    private boolean mIsLooping;

    private boolean mFirstFrameDecoded = false;
    private boolean mFirstFrameDecodedEventSent = false;
    private boolean mCalculateAudioLevel = false;

    protected DecoderInfo mVideoDecoderInfo;
    protected DecoderInfo mAudioDecoderInfo;
    protected Format mVideoFormat;
    protected Format mAudioFormat;

    private volatile boolean mIsRelease;

    private OnPreparedListener mOnPreparedListener;
    private OnCompletionListener mOnCompletionListener;
    private OnBufferingUpdateListener mOnBufferingUpdateListener;
    private OnSeekCompleteListener mOnSeekCompleteListener;
    private OnVideoSizeChangedListener mOnVideoSizeChangedListener;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener mOnInfoListener;
    private OnLoopStartListener mOnLoopStartListener;

    private AudioEventListener mAudioEventListener;
    private AudioFrameManager mAudioFrameManager;

    private double mLastAudioLevelDuration = C.LENGTH_UNSET;
    private double mLastAudioLevelEnergy = C.LENGTH_UNSET;

    private boolean mLoopingPlaySeek;
    private int mLastBufferedPercentage;

    private ArrayList<Long> mClipDurations = new ArrayList<>(); // total duration in milliseconds

    static {
        try {
            MediaCodecUtil.getDecoderInfo("video/hevc", false);
            MediaCodecUtil.getDecoderInfo("video/avc", false);
            MediaCodecUtil.getDecoderInfo("audio/mp4a-latm", false); // aac
        } catch (MediaCodecUtil.DecoderQueryException ex) {
            ex.printStackTrace(); // ignore
        }
    }

    public static void setLogger(ILogger logger) {
        synchronized (ExoMediaPlayer.class) {
            sLogger = logger;
        }
    }

    public static ILogger getLogger() {
        if (sLogger == null) {
            synchronized (ExoMediaPlayer.class) {
                if (sLogger == null) {
                    sLogger = new DefaultLogger();
                }
            }
        }
        return sLogger;
    }

    public ExoMediaPlayer(Context ctx) {
        mAppContext = ctx;
        mHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);
        mHandlerThread.start();
        mMainHandler = new Handler(mHandlerThread.getLooper());
        mStateStore = new StateStore();

        //listeners
        mExo2EventListener = new Exo2EventListener();
        BufferRepeatListener bufferRepeatListener = new BufferRepeatListener();

        mMediaSourceEventListener = new MediaSourceEventListener();
        mSurfaceListener = new SurfaceListener();

        //buffer update
        mBufferUpdateRepeater = new Repeater(mMainHandler);
        mBufferUpdateRepeater.setRepeaterDelay(BUFFER_REPEAT_DELAY);
        mBufferUpdateRepeater.setRepeatListener(bufferRepeatListener);

        RendererEventListener rendererEventListener = new RendererEventListener();

        //generate renderer
        RendererProvider rendererProvider = new DefaultRendererProvider(mAppContext, mMainHandler,
                rendererEventListener,
                rendererEventListener,
                rendererEventListener,
                rendererEventListener);
        mRenderers = rendererProvider.generate();

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (ExoMediaPlayer.this) {
                    initPlayer();
                    ExoMediaPlayer.this.notifyAll();
                }
            }
        });
        
        synchronized (this) {
            while(mExoPlayer == null) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    //init player, looper使用自己的
    private void initPlayer() {
        //track selection
        TrackSelection.Factory selectionFactory = new AdaptiveTrackSelection.Factory(new DefaultBandwidthMeter());
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(selectionFactory);
        LoadControl loadControl = new QLoadControl();

        mExoPlayer = ExoPlayerFactory.newInstance(mRenderers.toArray(new Renderer[mRenderers.size()]),
                trackSelector, loadControl);
        mExoPlayer.addListener(mExo2EventListener);
    }

    // IMediaPlayer API
    @Override
    public void setDataSource(Context context, Uri uri) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        mMediaSource = buildMediaSource(mAppContext, uri, null);
    }

    @Override
    public void setDataSource(Context context, List<String> pathList) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        ArrayList<MediaSource> sources = new ArrayList<MediaSource>();
        for (String path : pathList) {
            if (!TextUtils.isEmpty(path)) {
                sources.add(buildMediaSource(mAppContext, Uri.parse(path), null));
            }
        }
        mMediaSource = new ConcatenatingMediaSource(sources.toArray(new MediaSource[sources.size()]));
    }

    @Override
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        // TODO: handle headers
        setDataSource(context, uri);
    }

    @Override
    public void setDataSource(String path) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(mAppContext, Uri.parse(path));
    }

    @Override
    public void setDataSource(List<VideoMeta> metas) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        mMediaSource = new DynamicConcatenatingMediaSource();
        for (VideoMeta meta : metas) {
            long start = meta.startPosition * 1000;
            long end = (meta.endPosition == VideoMeta.OPEN_ENDED ? meta.duration : meta.endPosition) * 1000;
            if (end <= start) {
                throw new IllegalArgumentException("wrong range [" + start + "," + end + "]");
            }
            long clipDuration = (end - start) / 1000;
            mClipDurations.add(clipDuration);
            MediaSource source = buildMediaSource(mAppContext, Uri.parse(meta.uri), null);
            ((DynamicConcatenatingMediaSource) mMediaSource).addMediaSource(new ClippingMediaSource(source, start, end));
        }
    }

    public void setDataSource(VideoMeta... videoMetas) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(Arrays.asList(videoMetas));
    }

    @Override
    public void prepareAsync() throws IllegalStateException {
        getLogger().v(TAG, "prepareAsync");
        if (mSurface != null) {
            setSurface(mSurface);
        }
        if (mIsLooping) {
            getLogger().v(TAG, "looping play video");
//            mMediaSource = new LoopingMediaSource(mMediaSource);
        }
        mExoPlayer.setPlayWhenReady(false);
        mExoPlayer.prepare(mMediaSource);
    }

    @Override
    public void start() throws IllegalStateException {
        if (mExoPlayer == null) {
            return;
        }
        mExoPlayer.setPlayWhenReady(true);
        if (!mFirstFrameDecodedEventSent &&
                mFirstFrameDecoded) {
            notifyOnInfo(MEDIA_INFO_VIDEO_RENDERING_START, 0);
            mFirstFrameDecodedEventSent = true;
        }
    }

    @Override
    public void stop() throws IllegalStateException {
        if (mExoPlayer == null) {
            return;
        }
        if (!mStopped.getAndSet(true)) {
            mExoPlayer.setPlayWhenReady(false);
            mExoPlayer.stop();
        }
    }

    @Override
    public void pause() throws IllegalStateException {
        if (mExoPlayer == null) {
            return;
        }
        mExoPlayer.setPlayWhenReady(false);
    }

    @Override
    public void seekTo(long positionMs) throws IllegalStateException {
        if (mExoPlayer == null) {
            return;
        }
        if (mClipDurations.size() > 0) {
            long duration = 0;
            for (int i=0; i<mClipDurations.size(); i++) {
                duration += mClipDurations.get(i);
                if (duration > positionMs) {
                    mExoPlayer.seekTo(i, positionMs - duration + mClipDurations.get(i));
                    break;
                }
            }
        } else {
            mExoPlayer.seekTo(positionMs);
        }
        mStateStore.setMostRecentState(mStateStore.isLastReportedPlayWhenReady(), StateStore.STATE_SEEKING);
    }

    @Override
    public void reset() {
        if (mExoPlayer != null) {
            setBufferRepeaterStarted(false);

            if (mExoPlayer != null) {
                mExoPlayer.setPlayWhenReady(false);
                mExoPlayer.stop(true);
            }
            mIsLooping = false;
            mFirstFrameDecoded = false;
            mFirstFrameDecodedEventSent = false;
            mStateStore.reset();
            mClipDurations.clear();
            mLastBufferedPercentage = 0;
        }
    }

    @Override
    public synchronized void release() {
        mIsRelease = true;
        if (mExoPlayer != null) {
            setBufferRepeaterStarted(false);

            if (mExoPlayer != null) {
                mExoPlayer.release();
                mExoPlayer.removeListener(mExo2EventListener);
                mExoPlayer = null;
            }

            mSurface = null;
            mVideoWidth = 0;
            mVideoHeight = 0;
            mSurfaceHolder = null;
            mClipDurations.clear();
            mLastBufferedPercentage = 0;
        }

        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }
        stayAwake(false);
        updateSurfaceScreenOn();
        removeSurfaceCallbacks();
        if (mSurface != null) {
            if (mOwnsSurface) {
                mSurface.release();
            }
            mSurface = null;
        }
        // reset listeners
        mOnPreparedListener = null;
        mOnCompletionListener = null;
        mOnBufferingUpdateListener = null;
        mOnSeekCompleteListener = null;
        mOnVideoSizeChangedListener = null;
        mOnErrorListener = null;
        mOnInfoListener = null;
    }

    @Override
    public long getDuration() {
        if (mExoPlayer == null)
            return 0;
        if (mClipDurations.size() > 0) {
            long totalDuration = 0;
            for (Long clipDuration : mClipDurations) {
                totalDuration += clipDuration;
            }
            return totalDuration;
        }
        return mExoPlayer.getDuration();
    }

    @Override
    public long getCurrentPosition() {
        if (mExoPlayer == null)
            return 0;
        if (mClipDurations.size() > 0) {
            int idx = mExoPlayer.getCurrentWindowIndex();
            long offset = 0;
            while (idx > 0) {
                idx--;
                offset += mClipDurations.get(idx);
            }
            return offset + mExoPlayer.getCurrentPosition();
        }
        return mExoPlayer.getCurrentPosition();
    }

    @Override
    public int getVideoWidth() {
        return mVideoWidth;
    }

    @Override
    public int getVideoHeight() {
        return mVideoHeight;
    }

    @Override
    public boolean isPlaying() {
        if (mExoPlayer == null)
            return false;
        int state = mExoPlayer.getPlaybackState();
        switch (state) {
            case ExoPlayer.STATE_IDLE:
            case ExoPlayer.STATE_READY:
                return mExoPlayer.getPlayWhenReady();
            case ExoPlayer.STATE_BUFFERING:
            case ExoPlayer.STATE_ENDED:
                // current looping implementation has may trigger ENDED STATE
                // we should reimplement looping feature by exoplayer's standard
                // if (mIsLooping && state == ExoPlayer.STATE_ENDED) return true;

                // after first time play the state goes to
                // STATE_ENDED -> STATE_BUFFERING -> READY
                // wei should return true under whatever circumstances if the video is at looping
                // play mode
                if (mIsLooping) return true;
            default:
                return false;
        }
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        //TODO: rightVolume not handled
        sendMessage(C.TRACK_TYPE_AUDIO, C.MSG_SET_VOLUME, leftVolume);
    }

    @Override
    public void setRate(float rate) {
        PlaybackParameters params = new PlaybackParameters(rate, rate);
        mExoPlayer.setPlaybackParameters(params);
    }

    @Override
    public void setCalculateAudioLevel(boolean isNeedAudioLevel) {
        mCalculateAudioLevel = isNeedAudioLevel;
    }

    @Override
    public double getAudioLevel() {
        double level = 0d;
        if (mAudioFrameManager != null) {
            double duration = mAudioFrameManager.getDuration() - mLastAudioLevelDuration;
            if (duration > 0) {
                level =  Math.sqrt((mAudioFrameManager.getAudioEnergy() - mLastAudioLevelEnergy) / duration);
            }
            mLastAudioLevelDuration = mAudioFrameManager.getDuration();
            mLastAudioLevelEnergy = mAudioFrameManager.getAudioEnergy();
        }
        return level;
    }

    @Override
    public double getAudioDuration() {
        if (mAudioFrameManager != null) {
            return mAudioFrameManager.getDuration();
        }
        return 0d;
    }

    @Override
    public double getAudioEnergy() {
        if (mAudioFrameManager != null) {
            return mAudioFrameManager.getAudioEnergy();
        }
        return 0d;
    }

    @Override
    public boolean isLooping() {
        return mIsLooping;
    }

    @Override
    public void setLooping(boolean looping) {
        mIsLooping = looping;
        getLogger().i(TAG, "setLooping " + mIsLooping);
    }

    @Override
    public void setDisplay(SurfaceHolder sh) {
        removeSurfaceCallbacks();
        mSurfaceHolder = sh;
        if (sh == null) {
            setVideoSurfaceInternal(null, false);
        } else {
            setVideoSurfaceInternal(sh.getSurface(), false);
            sh.addCallback(mSurfaceListener);
        }
    }

    @Override
    public void setSurface(Surface surface) {
        if (mScreenOnWhilePlaying && surface != null) {
            getLogger().w(TAG, "setScreenOnWhilePlaying(true) is ineffective for Surface");
        }
        removeSurfaceCallbacks();
        setVideoSurfaceInternal(surface, false);
    }

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) {
        if (mScreenOnWhilePlaying != screenOn) {
            if (screenOn && mSurfaceHolder == null) {
                getLogger().w(TAG, "setScreenOnWhilePlaying(true) is ineffective without a SurfaceHolder");
            }
            mScreenOnWhilePlaying = screenOn;
            updateSurfaceScreenOn();
        }
        updateSurfaceScreenOn();
    }

    @Override
    public void setWakeMode(Context context, int mode) {
        boolean wasHeld = false;
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                wasHeld = true;
                mWakeLock.release();
            }

            mWakeLock = null;
        }

        try {
            //Acquires the wakelock if we have permissions
            if (context.getPackageManager().checkPermission(Manifest.permission.WAKE_LOCK, context.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(mode | PowerManager.ON_AFTER_RELEASE, ExoMediaPlayer.class.getName());
                mWakeLock.setReferenceCounted(false);
            } else {
                getLogger().w(TAG, "Unable to acquire WAKE_LOCK due to missing manifest permission");
            }
        } catch (Exception ex) {
            // android.os.DeadObjectException java.lang.RuntimeException:Package manager has died
            getLogger().w(TAG, "Unable to acquire WAKE_LOCK ", ex);
        }

        stayAwake(wasHeld);
    }

    @Override
    public void setNextMediaPlayer(MediaPlayerInterface nextMediaPlayer) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("setNextMediaPlayer is not supported by " + ExoMediaPlayer.class.getSimpleName());
    }

    @Override
    public void setOnPreparedListener(OnPreparedListener listener) {
        mOnPreparedListener = listener;
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener listener) {
        mOnCompletionListener = listener;
    }

    @Override
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        mOnBufferingUpdateListener = listener;
        setBufferRepeaterStarted(listener != null);
    }

    @Override
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        mOnSeekCompleteListener = listener;
    }

    @Override
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        mOnVideoSizeChangedListener = listener;
    }

    @Override
    public void setOnErrorListener(OnErrorListener listener) {
        mOnErrorListener = listener;
    }

    @Override
    public void setOnInfoListener(OnInfoListener listener) {
        mOnInfoListener = listener;
    }

    @Override
    public void setOnLoopStartListener(OnLoopStartListener listener) {
        mOnLoopStartListener = listener;
    }

    public void setAudioEventListener(AudioEventListener listener) {
        mAudioEventListener = listener;
    }

    // clear surface the player is rendering else do nothing
    private void clearVideoSurface(Surface surface) {
        if (surface != null && surface == mSurface) {
            setSurface(null);
        }
    }

    private void clearVideoSurfaceHolder(SurfaceHolder sh) {
        if (sh != null && sh == mSurfaceHolder) {
            setDisplay(null);
        }
    }

    private void setVideoSurfaceView(SurfaceView surfaceView) {
        setDisplay(surfaceView == null ? null : surfaceView.getHolder());
    }

    private void clearVideoSurfaceView(SurfaceView surfaceView) {
        clearVideoSurfaceHolder(surfaceView == null ? null : surfaceView.getHolder());
    }

    private void setVideoTextureView(TextureView textureView) {
        removeSurfaceCallbacks();
        mTextureView = textureView;
        if (textureView == null) {
            setVideoSurfaceInternal(null, true);
        } else {
            if (textureView.getSurfaceTextureListener() != null) {
                getLogger().w(TAG, "Replacing existing SurfaceTextureListener");
            }
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            setVideoSurfaceInternal(surfaceTexture == null ? null : new Surface(surfaceTexture), true);
            textureView.setSurfaceTextureListener(mSurfaceListener);
        }
    }

    private void clearVideoTextureView(TextureView textureView) {
        if (textureView != null && textureView == mTextureView) {
            setVideoTextureView(null);
        }
    }

    private void removeSurfaceCallbacks() {
        if (mTextureView != null) {
            if (mTextureView.getSurfaceTextureListener() != mSurfaceListener) {
                getLogger().w(TAG, "SurfaceTextureListener already unset or replaced");
            } else {
                mTextureView.setSurfaceTextureListener(null);
            }
            mTextureView = null;
        }
        if (mSurfaceHolder != null) {
            mSurfaceHolder.removeCallback(mSurfaceListener);
            mSurfaceHolder = null;
        }
    }

    private void setVideoSurfaceInternal(Surface surface, boolean ownsSurface) {
        if (mExoPlayer == null) {
            getLogger().w(TAG, "call setVideoSurfaceInternal after release");
            return;
        }
        if (mSurface != null && mSurface != surface && surface != null) {
            if (mOwnsSurface) {
                mSurface.release();
            }
            sendMessage(C.TRACK_TYPE_VIDEO, C.MSG_SET_SURFACE, surface, true);
            final long pos = mExoPlayer.getCurrentPosition();
            if (Util.SDK_INT < 23) { // workaround for switching surface issue #318
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mExoPlayer != null) {
                            mExoPlayer.seekTo(pos);
                        }
                    }
                }, 200);
            }
        } else {
            sendMessage(C.TRACK_TYPE_VIDEO, C.MSG_SET_SURFACE, surface, false);
        }
        mSurface = surface;
        mOwnsSurface = ownsSurface;
        updateSurfaceScreenOn();
    }

    public int getAudioSessionId() {
        return mAudioSessionId;
    }

    private void stayAwake(boolean awake) {
        if (mWakeLock != null) {
            if (awake && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            } else if (!awake && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        mStayAwake = awake;
        updateSurfaceScreenOn();
    }

    private void updateSurfaceScreenOn() {
        if (mSurfaceHolder != null) {
            mSurfaceHolder.setKeepScreenOn(mScreenOnWhilePlaying && mStayAwake);
        }
    }

    private int getBufferedPercentage() {
        if (mExoPlayer == null)
            return 0;
        return mExoPlayer.getBufferedPercentage();
    }

    private class MediaSourceEventListener implements AdaptiveMediaSourceEventListener,
            ExtractorMediaSource.EventListener {
        // AdaptiveMediaSourceEventListener
        @Override
        public void onLoadStarted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat,
                                  int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs,
                                  long mediaEndTimeMs, long elapsedRealtimeMs) {
            // Do nothing.
        }

        @Override
        public void onLoadCompleted(DataSpec dataSpec, int dataType, int trackType, Format trackFormat,
                                    int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs,
                                    long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {
            // Do nothing.
        }

        @Override
        public void onLoadCanceled(DataSpec dataSpec, int dataType, int trackType, Format trackFormat,
                                   int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs,
                                   long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded) {
            // Do nothing.
        }

        @Override
        public void onLoadError(DataSpec dataSpec, int dataType, int trackType, Format trackFormat,
                                int trackSelectionReason, Object trackSelectionData, long mediaStartTimeMs,
                                long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs, long bytesLoaded,
                                IOException error, boolean wasCanceled) {
            getLogger().d(TAG, "AdaptiveMediaSourceEventListener loadError " + error
                    + "\n" + ExoMediaPlayerUtils.getPrintableStackTrace(error));
        }

        @Override
        public void onUpstreamDiscarded(int trackType, long mediaStartTimeMs, long mediaEndTimeMs) {
            // Do nothing.
        }

        @Override
        public void onDownstreamFormatChanged(int trackType, Format trackFormat, int trackSelectionReason,
                                              Object trackSelectionData, long mediaTimeMs) {
            // Do nothing.
        }

        //  ExtractorMediaSource.EventListener
        @Override
        public void onLoadError(IOException error) {
            getLogger().d(TAG, "ExtractorMediaSource loadError " + error
                    + "\n" + ExoMediaPlayerUtils.getPrintableStackTrace(error));
        }
    }

    private class SurfaceListener implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {
        // surfaceholder callback
        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            // do nothing
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            setVideoSurfaceInternal(null, false);
        }

        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            setVideoSurfaceInternal(surfaceHolder.getSurface(), false);
        }

        // textureview callback
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            setVideoSurfaceInternal(new Surface(surfaceTexture), true);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {
            // do nothing
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            setVideoSurfaceInternal(null, true);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // do nothing
        }
    }

    private class RendererEventListener implements
            VideoRendererEventListener,
            AudioRendererEventListener,
            MetadataRenderer.Output,
            TextRenderer.Output {
        // VideoRendererEventListener
        @Override
        public void onVideoEnabled(DecoderCounters decoderCounters) {
            getLogger().d(TAG, "onVideoEnabled");
        }

        @Override
        public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
            getLogger().d(TAG, "onAudioDecoderInitialized decoderName=" + decoderName
                    +",initializedTimestampMs=" + initializedTimestampMs
                    +",initializationDurationMs=" + initializationDurationMs);
            mVideoDecoderInfo = new DecoderInfo(DecoderInfo.TYPE_VIDEO, decoderName, initializationDurationMs);
        }

        @Override
        public void onVideoInputFormatChanged(Format format) {
            getLogger().d(TAG, "onVideoInputFormatChanged");
            mVideoFormat = format;
        }

        @Override
        public void onDroppedFrames(int count, long elapsedMs) {
            getLogger().d(TAG, "onDroppedFrames");
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            mVideoWidth = width;
            mVideoHeight = height;
            notifyOnVideoSizeChanged(width, height, 1, 1);
            if (unappliedRotationDegrees > 0) {
                notifyOnInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, unappliedRotationDegrees);
            }
        }

        @Override
        public void onRenderedFirstFrame(Surface surface) {
            getLogger().d(TAG, "onRenderedFirstFrame");
            if (mExoPlayer != null && ExoMediaPlayer.this.mSurface == surface) {
                if (mExoPlayer.getPlayWhenReady() && !mFirstFrameDecodedEventSent) { // avoid preparing ---> started
                    notifyOnInfo(MEDIA_INFO_VIDEO_RENDERING_START, 0);
                    mFirstFrameDecodedEventSent = true;
                }
            }
            mFirstFrameDecoded = true;
        }

        @Override
        public void onVideoDisabled(DecoderCounters decoderCounters) {
            getLogger().d(TAG, "onVideoDisabled");
        }

        // AudioRendererEventListener
        @Override
        public void onAudioEnabled(DecoderCounters decoderCounters) {
            getLogger().d(TAG, "onAudioEnabled");
            mAudioSessionId = C.AUDIO_SESSION_ID_UNSET;
        }

        @Override
        public void onAudioSessionId(int sessionId) {
            getLogger().d(TAG, "onAudioSessionId " + sessionId);
            mAudioSessionId = sessionId;
        }

        @Override
        public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
            getLogger().d(TAG, "onAudioDecoderInitialized decoderName=" + decoderName
                    +",initializedTimestampMs" + initializedTimestampMs
                    +",initializationDurationMs" + initializationDurationMs);
            mAudioDecoderInfo = new DecoderInfo(DecoderInfo.TYPE_AUDIO, decoderName, initializationDurationMs);
        }

        @Override
        public void onAudioInputFormatChanged(Format format) {
            getLogger().d(TAG, "onAudioInputFormatChanged");
            mAudioFormat = format;
        }

        @Override
        public void onAudioSinkUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
            getLogger().d(TAG, "onAudioSinkUnderrun bufferSize=" + bufferSize
                    + ",bufferSizeMs" + bufferSizeMs
                    + ",elapsedSinceLastFeedMs" + elapsedSinceLastFeedMs);
        }

        @Override
        public void onAudioDisabled(DecoderCounters decoderCounters) {

        }

        @Override
        public boolean isNeedAudioData() {
            return mAudioEventListener != null || mCalculateAudioLevel;
        }

        @Override
        public void onRenderAudioData(byte[] audioData) {
            getLogger().v(TAG, "onRenderAudioData " + audioData.length);
            if (mCalculateAudioLevel && mAudioFrameManager == null) {
                mAudioFrameManager = new AudioFrameManager();
            }
            if (mAudioFrameManager != null) {
                mAudioFrameManager.feedAudioData(audioData, mAudioFormat);
            }
            if (mAudioEventListener != null) {
                mAudioEventListener.onRenderAudioData(audioData, mAudioFormat);
            }
        }

        // MetadataRenderer.Output
        @Override
        public void onMetadata(Metadata metadata) {
            getLogger().d(TAG, "onMetadata");
        }

        // TextRenderer.Output
        @Override
        public void onCues(List<Cue> list) {
            getLogger().d(TAG, "onCues");
        }
    }

    private class BufferRepeatListener implements
            Repeater.RepeatListener {
        // BufferUpdate Repeater
        @Override
        public void onUpdate() {
            synchronized (ExoMediaPlayer.this) {
                if (mIsRelease) {
                    return;
                }
                if (mExoPlayer != null) {
                    int state = mExoPlayer.getPlaybackState();
                    switch (state) {
                        case ExoPlayer.STATE_IDLE:
                        case ExoPlayer.STATE_ENDED:
                            setBufferRepeaterStarted(false);
                            break;
                        case ExoPlayer.STATE_READY:
                        case ExoPlayer.STATE_BUFFERING:
                            int percent = getBufferedPercentage();
                            if (percent != mLastBufferedPercentage) {
                                notifyOnBufferingUpdate(percent);
                                mLastBufferedPercentage = percent;
                            }
                            break;
//                    default:
                        // no op
                    }
                }
            }
        }
    }

    private class Exo2EventListener implements
            ExoPlayer.EventListener  {
        // ExoPlayer.EventListener
        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
            getLogger().d(TAG, "onTimelineChanged reason=" + reason);
        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            getLogger().d(TAG, "onTracksChanged");
            if (mClipDurations.size() > 0) {
                getLogger().d(TAG, "update duration idx=" + mExoPlayer.getCurrentWindowIndex() + ",duration=" + mExoPlayer.getDuration());
                mClipDurations.set(mExoPlayer.getCurrentWindowIndex(), mExoPlayer.getDuration());
            }
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
            getLogger().d(TAG, "onLoadingChanged " + isLoading);
            if (isLoading) {
                setBufferRepeaterStarted(true);
            }
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            getLogger().d(TAG, "onPlayerStateChanged playWhenReady=" + playWhenReady
                    + ",playbackState=" + playbackState);
            reportPlayerState();
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            if (mExoPlayer != null) { // cz no error state
                setBufferRepeaterStarted(false);
            }
            getLogger().e(TAG, "ExoPlaybackException " + error + "\n"
                    + ExoMediaPlayerUtils.getPrintableStackTrace(error));
            if (error != null) {
                Throwable cause = error.getCause();
                if (cause != null) {
                    if (cause instanceof HttpDataSource.HttpDataSourceException) {
                        if (cause.toString().contains("Unable to connect")) {
                            boolean hasNetwork = ExoMediaPlayerUtils.isNetworkAvailable(mAppContext);
                            getLogger().e(TAG, "ExoPlaybackException hasNetwork=" + hasNetwork
                                    + " caused by:\n"+ cause.toString());
                            if (!hasNetwork) {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_EXTRA_NETWORK);
                            } else {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_EXTRA_CONN);
                            }
                            return;
                        } else if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            String shortReason = cause.toString();
                            if (shortReason.contains("403")) {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_RESPONSE_403);
                            } else if (shortReason.contains("404")) {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_RESPONSE_404);
                            } else if (shortReason.contains("500")) {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_RESPONSE_500);
                            } else if (shortReason.contains("502")) {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_RESPONSE_502);
                            } else {
                                notifyOnError(EXO_MEDIA_ERROR_WHAT_IO, EXO_MEDIA_ERROR_RESPONSE_OTHER);
                            }
                            return;
                        }
                    } else if (cause instanceof UnrecognizedInputFormatException) {
                        getLogger().i(TAG, ExoMediaPlayerUtils.getLogcatContent());
                        notifyOnError(EXO_MEDIA_ERROR_WHAT_EXTRACTOR, EXO_MEDIA_ERROR_EXTRA_UNKNOWN);
                        return;
                    } else if (cause instanceof IllegalStateException) { // maybe throw by MediaCodec dequeueInputBuffer
                        getLogger().i(TAG, ExoMediaPlayerUtils.getLogcatContent());
                        notifyOnError(EXO_MEIDA_ERROR_ILLEGAL_STATE, EXO_MEDIA_ERROR_EXTRA_UNKNOWN);
                        return;
                    } else if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                        getLogger().i(TAG, ExoMediaPlayerUtils.getLogcatContent());
                        notifyOnError(EXO_MEIDA_ERROR_MEDIACODEC_DECODER_INIT, EXO_MEDIA_ERROR_EXTRA_UNKNOWN);
                        return;
                    }
                }
            }
            getLogger().i(TAG, ExoMediaPlayerUtils.getLogcatContent(0, null, 30));
            notifyOnError(EXO_MEDIA_ERROR_WHAT_UNKNOWN, EXO_MEDIA_ERROR_EXTRA_UNKNOWN);
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            getLogger().d(TAG, "onPositionDiscontinuity reason=" + reason);
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            getLogger().d(TAG, "onShuffleModeEnabledChanged shuffleModeEnabled=" + shuffleModeEnabled);
        }

        @Override
        public void onSeekProcessed() {
            getLogger().d(TAG, "onSeekProcessed");
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
            getLogger().d(TAG, "onPlaybackParametersChanged ["
                    + playbackParameters.speed + "," + playbackParameters.pitch + "]");
        }

        @Override
        public void onRepeatModeChanged(@Player.RepeatMode int repeatMode) {
            getLogger().d(TAG, "onRepeatModeChanged " + repeatMode);
        }
    }

    // StateStore
    private static class StateStore {
        private static final int FLAG_PLAY_WHEN_READY = 0xF0000000;
        private static final int STATE_SEEKING = 100;

        //We keep the last few states because that is all we need currently
        private int[] prevStates = new int[]{
                ExoPlayer.STATE_IDLE,
                ExoPlayer.STATE_IDLE,
                ExoPlayer.STATE_IDLE,
                ExoPlayer.STATE_IDLE
        };

        void setMostRecentState(boolean playWhenReady, int state) {
            int newState = getState(playWhenReady, state);
            getLogger().v(TAG, "request setMostRecentState [" + playWhenReady
                    + "," + state + "], lastState=" + prevStates[3] + ",newState=" + newState);
            if (prevStates[3] == newState) {
                return;
            }

            prevStates[0] = prevStates[1];
            prevStates[1] = prevStates[2];
            prevStates[2] = prevStates[3];
            prevStates[3] = newState;
            getLogger().v(TAG, "MostRecentState [" + prevStates[0]
                    + "," + prevStates[1]
                    + "," + prevStates[2]
                    + "," + prevStates[3] + "]");
        }

        int getState(boolean playWhenReady, int state) {
            return state | (playWhenReady ? FLAG_PLAY_WHEN_READY : 0);
        }

        int getMostRecentState() {
            return prevStates[3];
        }

        boolean isLastReportedPlayWhenReady() {
            return (prevStates[3] & FLAG_PLAY_WHEN_READY) != 0;
        }

        boolean matchesHistory(int[] states, boolean ignorePlayWhenReady) {
            boolean flag = true;
            int andFlag = ignorePlayWhenReady ? ~FLAG_PLAY_WHEN_READY : ~0x0;
            int startIndex = prevStates.length - states.length;

            for (int i = startIndex; i < prevStates.length; i++) {
                flag &= (prevStates[i] & andFlag) == (states[i - startIndex] & andFlag);
            }

            return flag;
        }

        void reset() {
            prevStates = new int[]{ExoPlayer.STATE_IDLE, ExoPlayer.STATE_IDLE, ExoPlayer.STATE_IDLE, ExoPlayer.STATE_IDLE};
        }
    }

    private void setBufferRepeaterStarted(boolean start) {
        getLogger().d(TAG, "setBufferRepeaterStarted " + start);
        if (start && mOnBufferingUpdateListener != null) {
            mBufferUpdateRepeater.start();
        } else {
            mBufferUpdateRepeater.stop();
        }
    }

    // reportPlayerState
    private synchronized void reportPlayerState() {
        if (mExoPlayer == null || mIsRelease) {
            return;
        }
        boolean playWhenReady = mExoPlayer.getPlayWhenReady();
        int playbackState = mExoPlayer.getPlaybackState();

        int newState = mStateStore.getState(playWhenReady, playbackState);
        if (newState != mStateStore.getMostRecentState()) {
            getLogger().d(TAG, "setMostRecentState [" + playWhenReady + "," + playbackState + "]");
            mStateStore.setMostRecentState(playWhenReady, playbackState);

            //Makes sure the buffering notifications are sent
            if (newState == ExoPlayer.STATE_READY) {
                setBufferRepeaterStarted(true);
            } else if (newState == ExoPlayer.STATE_IDLE || newState == ExoPlayer.STATE_ENDED) {
                setBufferRepeaterStarted(false);
            }

            if (newState == mStateStore.getState(true, ExoPlayer.STATE_ENDED)) {
                if (isLooping()) {
                    getLogger().i(TAG, "looping play start");
                    mLoopingPlaySeek = true;
                    seekTo(0);
                    if (mOnLoopStartListener != null) {
                        mOnLoopStartListener.onLoopStart(ExoMediaPlayer.this);
                    }
                } else {
                    notifyOnCompletion();
                }
                return;
            }

            // onPrepared
            boolean informPrepared = mStateStore.matchesHistory(new int[] {
                    mStateStore.getState(false, ExoPlayer.STATE_IDLE),
                    mStateStore.getState(false, ExoPlayer.STATE_BUFFERING),
                    mStateStore.getState(false, ExoPlayer.STATE_READY)}, false);
            if (informPrepared) {
                notifyOnPrepared();
                return;
            }

            //Because the playWhenReady isn't a state in itself, rather a flag to a state we will ignore informing of
            // see events when that is the only change.  Additionally, on some devices we get states ordered as
            // [seeking, ready, buffering, ready] while on others we get [seeking, buffering, ready]
            boolean informSeekCompletion = mStateStore.matchesHistory(new int[]{StateStore.STATE_SEEKING, ExoPlayer.STATE_BUFFERING, ExoPlayer.STATE_READY}, true);
            informSeekCompletion |= mStateStore.matchesHistory(new int[] {ExoPlayer.STATE_BUFFERING, StateStore.STATE_SEEKING, ExoPlayer.STATE_READY}, true);
            informSeekCompletion |= mStateStore.matchesHistory(new int[]{StateStore.STATE_SEEKING, ExoPlayer.STATE_READY, ExoPlayer.STATE_BUFFERING, ExoPlayer.STATE_READY}, true);

            if (informSeekCompletion) {
                if (!mLoopingPlaySeek) {
                    notifyOnSeekComplete();
                } else {
                    mLoopingPlaySeek = false;
                }
                return;
            }

            // Buffering Update
            boolean infoBufferingStart = mStateStore.matchesHistory(new int[] {
                    mStateStore.getState(true, ExoPlayer.STATE_READY),
                    mStateStore.getState(true, ExoPlayer.STATE_BUFFERING)
            }, false);

            if (infoBufferingStart) {
                notifyOnInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                return;
            }

            boolean infoBufferingEnd = mStateStore.matchesHistory(new int[] {
                    mStateStore.getState(true, ExoPlayer.STATE_BUFFERING),
                    mStateStore.getState(true, ExoPlayer.STATE_READY),
            }, false);

            if (infoBufferingEnd) {
                notifyOnInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                return;
            }
        }
    }

    //
    private void sendMessage(int renderType, int messageType, Object message) {
        sendMessage(renderType, messageType, message, false);
    }

    private void sendMessage(int renderType, int messageType, Object message, boolean blocking) {
        if (mRenderers.isEmpty()) {
            return;
        }

        List<ExoPlayer.ExoPlayerMessage> messages = new ArrayList<ExoPlayer.ExoPlayerMessage>();
        for (Renderer renderer : mRenderers) {
            if (renderer.getTrackType() == renderType) {
                messages.add(new ExoPlayer.ExoPlayerMessage(renderer, messageType, message));
            }
        }

        if (blocking) {
            mExoPlayer.blockingSendMessages(messages.toArray(new ExoPlayer.ExoPlayerMessage[messages.size()]));
        } else {
            mExoPlayer.sendMessages(messages.toArray(new ExoPlayer.ExoPlayerMessage[messages.size()]));
        }
    }

    // buildMediaSource
    private MediaSource buildMediaSource(Context context, Uri uri, String overrideExtension) {
        int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri)
                : Util.inferContentType("." + overrideExtension);
        String userAgent = Util.getUserAgent(context, "ExoMediaPlayer");
        switch (type) {
//            case C.TYPE_SS:
//                return new SsMediaSource(uri, buildDataSourceFactory(false, userAgent),
//                        new DefaultSsChunkSource.Factory(buildDataSourceFactory(false, userAgent)), mMainHandler, mMediaSourceEventListener);
//            case C.TYPE_DASH:
//                return new DashMediaSource(uri, buildDataSourceFactory(false, userAgent),
//                        new DefaultDashChunkSource.Factory(buildDataSourceFactory(false, userAgent)), mMainHandler, mMediaSourceEventListener);
            case C.TYPE_HLS:
                HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(buildDataSourceFactory(BANDWIDTH_METER, userAgent));
                return hlsFactory.createMediaSource(uri, mMainHandler, mMediaSourceEventListener);
            case C.TYPE_OTHER:
                ExtractorMediaSource.Factory extractorFactory= new ExtractorMediaSource.Factory(buildDataSourceFactory(BANDWIDTH_METER, userAgent));
                extractorFactory.setContinueLoadingCheckIntervalBytes(ExtractorMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES * 2);
                return extractorFactory.createMediaSource(uri, mMainHandler, mMediaSourceEventListener);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    // Utils
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter, String userAgent) {
        return buildDataSourceFactory(useBandwidthMeter ? BANDWIDTH_METER : null, userAgent);
    }

    private DataSource.Factory buildDataSourceFactory(DefaultBandwidthMeter bandwidthMeter, String userAgent) {
        return new DefaultDataSourceFactory(mAppContext, bandwidthMeter,
                buildHttpDataSourceFactory(bandwidthMeter, userAgent));
    }

    private HttpDataSource.Factory buildHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter, String userAgent) {
        return new DefaultHttpDataSourceFactory(userAgent, bandwidthMeter);
    }

    private void notifyOnPrepared() {
        getLogger().v(TAG, "notifyOnPrepared");
        synchronized (ExoMediaPlayer.this) {
            if (mIsRelease) {
                return;
            }
        }
        if (mOnPreparedListener != null) {
            mOnPreparedListener.onPrepared(this);
        }
    }

    private void notifyOnCompletion() {
        getLogger().v(TAG, "notifyOnCompletion");
        synchronized (ExoMediaPlayer.this) {
            if (mIsRelease) {
                return;
            }
        }
        if (mOnCompletionListener != null) {
            mOnCompletionListener.onCompletion(this);
        }
    }

    private void notifyOnBufferingUpdate(int percent) {
        getLogger().v(TAG, "notifyOnBufferingUpdate " + percent);
        synchronized (ExoMediaPlayer.this) {
            if (mIsRelease) {
                return;
            }
        }
        if (mOnBufferingUpdateListener != null) {
            mOnBufferingUpdateListener.onBufferingUpdate(this, percent);
        }
    }

    private void notifyOnSeekComplete() {
        getLogger().v(TAG, "notifyOnSeekComplete");
        synchronized (ExoMediaPlayer.this) {
            if (mIsRelease) {
                return;
            }
        }
        if (mOnSeekCompleteListener != null) {
            mOnSeekCompleteListener.onSeekComplete(this);
        }
    }

    private void notifyOnVideoSizeChanged(int width, int height,
                                          int sarNum, int sarDen) {
        getLogger().v(TAG, "notifyOnVideoSizeChanged [" + width + "," + height + "]");
        synchronized (ExoMediaPlayer.this) {
            if (mIsRelease) {
                return;
            }
        }
        if (mOnVideoSizeChangedListener != null) {
            mOnVideoSizeChangedListener.onVideoSizeChanged(this, width, height/*,
                    sarNum, sarDen*/);
        }
    }

    private boolean notifyOnError(int what, int extra) {
        getLogger().d(TAG, "notifyOnError [" + what + "," + extra + "]");
        return mOnErrorListener != null && mOnErrorListener.onError(this, what, extra);
    }

    private boolean notifyOnInfo(int what, int extra) {
        getLogger().d(TAG, "notifyOnInfo [" + what + "," + extra + "]");
        return mOnInfoListener != null && mOnInfoListener.onInfo(this, what, extra);
    }

    // 流控参数配置样例
    public static final String DEFAULT_LC_SAMPLE_CONFIG = "65536,15000,30000,2000,5000";

    // 修改流控参数配置
    public static void updateLoadControlConfig(QLoadControl.Config config) {
        getLogger().d(TAG, "updateLoadControlConfig " + config);
        QLoadControl.updateConfig(config);
    }

    // 修改流控参数配置，支持字符串
    public static void updateLoadControlConfig(String configStr) {
        getLogger().d(TAG, "updateLoadControlConfig " + configStr);
        QLoadControl.updateConfig(configStr);
    }

    public DecoderInfo getVideoDecoderInfo() {
        return mVideoDecoderInfo;
    }

    public DecoderInfo getAudioDecoderInfo() {
        return mAudioDecoderInfo;
    }

    public Format getVideoFormat() {
        return mVideoFormat;
    }

    public Format getAudioFormat() {
        return mAudioFormat;
    }
}