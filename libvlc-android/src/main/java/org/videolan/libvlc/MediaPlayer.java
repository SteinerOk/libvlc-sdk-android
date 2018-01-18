/*****************************************************************************
 * MediaPlayer.java
 *****************************************************************************
 * Copyright © 2015 VLC authors and VideoLAN
 *
 * Authors  Jean-Baptiste Kempf <jb@videolan.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.libvlc;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.SparseArray;

import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.libvlc.util.VLCUtil;

import java.io.File;

@SuppressWarnings("unused, JniMissingFunction")
public class MediaPlayer extends VLCObject<MediaPlayer.Event> {

    private Media mMedia = null;
    private boolean mPlaying = false;
    private boolean mPlayRequested = false;
    private boolean mAudioDeviceFromUser = false;
    private int mVoutCount = 0;
    private boolean mAudioReset = false;
    private String mAudioOutput = "android_audiotrack";
    private String mAudioOutputDevice = null;
    private boolean mAudioPlugRegistered = false;
    private final AWindow mWindow = new AWindow(new AWindow.SurfaceCallback() {
        @Override
        public void onSurfacesCreated(AWindow vout) {
            boolean play = false;
            boolean enableVideo = false;
            synchronized (MediaPlayer.this) {
                if (!mPlaying && mPlayRequested)
                    play = true;
                else if (mVoutCount == 0)
                    enableVideo = true;
            }
            if (play)
                play();
            else if (enableVideo)
                setVideoTrackEnabled(true);
        }

        @Override
        public void onSurfacesDestroyed(AWindow vout) {
            boolean disableVideo = false;
            synchronized (MediaPlayer.this) {
                if (mVoutCount > 0)
                    disableVideo = true;
            }
            if (disableVideo)
                setVideoTrackEnabled(false);
        }
    });
    private String mAudioPlugOutputDevice = "stereo";
    private final BroadcastReceiver mAudioPlugReceiver =
            AndroidUtil.isLolliPopOrLater && !AndroidUtil.isMarshMallowOrLater ? createAudioPlugReceiver() : null;
    private final AudioDeviceCallback mAudioDeviceCallback =
            AndroidUtil.isMarshMallowOrLater ? createAudioDeviceCallback() : null;

    /**
     * Create an empty MediaPlayer
     *
     * @param libVLC a valid libVLC
     */
    public MediaPlayer(LibVLC libVLC) {
        super(libVLC);
        nativeNewFromLibVlc(libVLC, mWindow);
    }

    /**
     * Create a MediaPlayer from a Media
     *
     * @param media a valid Media object
     */
    public MediaPlayer(Media media) {
        super(media);
        if (media == null || media.isReleased())
            throw new IllegalArgumentException("Media is null or released");
        mMedia = media;
        mMedia.retain();
        nativeNewFromMedia(mMedia, mWindow);
    }

    @SuppressWarnings("unused") /* Used from JNI */
    private static Title createTitleFromNative(long duration, String name, int flags) {
        return new Title(duration, name, flags);
    }

    @SuppressWarnings("unused") /* Used from JNI */
    private static Chapter createChapterFromNative(long timeOffset, long duration, String name) {
        return new Chapter(timeOffset, duration, name);
    }

    @SuppressWarnings("unused") /* Used from JNI */
    private static TrackDescription createTrackDescriptionFromNative(int id, String name) {
        return new TrackDescription(id, name);
    }

    private void updateAudioOutputDevice(long encodingFlags, String defaultDevice) {
        final String newDeviceId = encodingFlags != 0 ? "encoded:" + encodingFlags : defaultDevice;
        if (!newDeviceId.equals(mAudioPlugOutputDevice)) {
            mAudioPlugOutputDevice = newDeviceId;
            setAudioOutputDeviceInternal(mAudioPlugOutputDevice, false);
        }
    }

    private boolean isEncoded(int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_AC3:
            case AudioFormat.ENCODING_E_AC3:
            case 14 /* AudioFormat.ENCODING_DOLBY_TRUEHD */:
            case AudioFormat.ENCODING_DTS:
            case AudioFormat.ENCODING_DTS_HD:
                return true;
            default:
                return false;
        }
    }

    private long getEncodingFlags(int encodings[]) {
        if (encodings == null)
            return 0;
        long encodingFlags = 0;
        for (int encoding : encodings) {
            if (isEncoded(encoding))
                encodingFlags |= 1 << encoding;
        }
        return encodingFlags;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private BroadcastReceiver createAudioPlugReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (action == null)
                    return;
                if (action.equalsIgnoreCase(AudioManager.ACTION_HDMI_AUDIO_PLUG)) {
                    final boolean hasHdmi = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1;
                    final long encodingFlags = !hasHdmi ? 0 :
                            getEncodingFlags(intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS));
                    updateAudioOutputDevice(encodingFlags, "stereo");
                }
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void registerAudioPlugV21(boolean register) {
        if (register) {
            final IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG);
            final Intent stickyIntent = mLibVLC.mAppContext.registerReceiver(mAudioPlugReceiver, intentFilter);
            if (stickyIntent != null)
                mAudioPlugReceiver.onReceive(mLibVLC.mAppContext, stickyIntent);
        } else {
            mLibVLC.mAppContext.unregisterReceiver(mAudioPlugReceiver);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private AudioDeviceCallback createAudioDeviceCallback() {

        return new AudioDeviceCallback() {

            private SparseArray<Long> mEncodedDevices = new SparseArray<>();

            private void onAudioDevicesChanged() {
                long encodingFlags = 0;
                for (int i = 0; i < mEncodedDevices.size(); ++i)
                    encodingFlags |= mEncodedDevices.valueAt(i);

                updateAudioOutputDevice(encodingFlags, "pcm");
            }

            @RequiresApi(Build.VERSION_CODES.M)
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                for (AudioDeviceInfo info : addedDevices) {
                    if (!info.isSink())
                        continue;
                    long encodingFlags = getEncodingFlags(info.getEncodings());
                    if (encodingFlags != 0)
                        mEncodedDevices.put(info.getId(), encodingFlags);
                }
                onAudioDevicesChanged();
            }

            @RequiresApi(Build.VERSION_CODES.M)
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                for (AudioDeviceInfo info : removedDevices) {
                    if (!info.isSink())
                        continue;
                    mEncodedDevices.remove(info.getId());
                }
                onAudioDevicesChanged();
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void registerAudioPlugV23(boolean register) {
        AudioManager am = (AudioManager) mLibVLC.mAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (register) {
            mAudioDeviceCallback.onAudioDevicesAdded(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
            am.registerAudioDeviceCallback(mAudioDeviceCallback, null);
        } else {
            am.unregisterAudioDeviceCallback(mAudioDeviceCallback);
        }
    }

    private void registerAudioPlug(boolean register) {
        if (register == mAudioPlugRegistered)
            return;
        if (mAudioDeviceCallback != null)
            registerAudioPlugV23(register);
        else if (mAudioPlugReceiver != null)
            registerAudioPlugV21(register);
        mAudioPlugRegistered = register;
    }

    /**
     * Get the IVLCVout helper.
     */
    public IVLCVout getVLCVout() {
        return mWindow;
    }

    /**
     * Get the Media used by this MediaPlayer. This Media should be released with {@link #release()}.
     */
    public synchronized Media getMedia() {
        if (mMedia != null)
            mMedia.retain();
        return mMedia;
    }

    /**
     * Set a Media
     *
     * @param media a valid Media object
     */
    public void setMedia(Media media) {
        if (media != null) {
            if (media.isReleased())
                throw new IllegalArgumentException("Media is released");
            media.setDefaultMediaPlayerOptions();
        }
        nativeSetMedia(media);
        synchronized (this) {
            if (mMedia != null) {
                mMedia.release();
            }
            if (media != null)
                media.retain();
            mMedia = media;
        }
    }

    /**
     * Play the media
     */
    public void play() {
        synchronized (this) {
            if (!mPlaying) {
                /* HACK: stop() reset the audio output, so set it again before first play. */
                if (mAudioReset) {
                    if (mAudioOutput != null)
                        nativeSetAudioOutput(mAudioOutput);
                    if (mAudioOutputDevice != null)
                        nativeSetAudioOutputDevice(mAudioOutputDevice);
                    mAudioReset = false;
                }
                if (!mAudioDeviceFromUser)
                    registerAudioPlug(true);
                mPlayRequested = true;
                if (mWindow.areSurfacesWaiting())
                    return;
            }
            mPlaying = true;
        }
        nativePlay();
    }

    /**
     * Stops the playing media
     */
    public void stop() {
        synchronized (this) {
            mPlayRequested = false;
            mPlaying = false;
            mAudioReset = true;
        }
        nativeStop();
    }

    /**
     * Set if, and how, the video title will be shown when media is played
     *
     * @param position see {@link Position}
     * @param timeout
     */
    public void setVideoTitleDisplay(int position, int timeout) {
        nativeSetVideoTitleDisplay(position, timeout);
    }

    /**
     * Get the current video scaling factor
     *
     * @return the currently configured zoom factor, or 0. if the video is set to fit to the
     * output window/drawable automatically.
     */
    public float getScale() {
        return nativeGetScale();
    }

    /**
     * Set the video scaling factor
     * <p>
     * That is the ratio of the number of pixels on screen to the number of pixels in the original
     * decoded video in each dimension. Zero is a special value; it will adjust the video to the
     * output window/drawable (in windowed mode) or the entire screen.
     *
     * @param scale the scaling factor, or zero
     */
    public void setScale(float scale) {
        nativeSetScale(scale);
    }

    /**
     * Get current video aspect ratio
     *
     * @return the video aspect ratio or NULL if unspecified
     */
    public String getAspectRatio() {
        return nativeGetAspectRatio();
    }

    /**
     * Set new video aspect ratio.
     *
     * @param aspect new video aspect-ratio or NULL to reset to default
     */
    public void setAspectRatio(String aspect) {
        nativeSetAspectRatio(aspect);
    }

    /**
     * Update the video viewpoint information
     *
     * @param yaw      View point yaw in degrees
     * @param pitch    View point pitch in degrees
     * @param roll     View point roll in degrees
     * @param fov      Field of view in degrees (default 80.0f)
     * @param absolute if true replace the old viewpoint with the new one. If false,
     *                 increase/decrease it.
     * @return true on success.
     */
    public boolean updateViewpoint(float yaw, float pitch, float roll, float fov, boolean absolute) {
        return nativeUpdateViewpoint(yaw, pitch, roll, fov, absolute);
    }

    /**
     * Selects an audio output module.
     * Any change will take effect only after playback is stopped and
     * restarted. Audio output cannot be changed while playing.
     * <p>
     * By default, the "android_audiotrack" is selected. Starting Android 21, passthrough is
     * enabled for encodings supported by the device/audio system.
     * <p>
     * Calling this method will disable the encoding detection.
     *
     * @return true on success.
     */
    public boolean setAudioOutput(String aout) {
        final boolean ret = nativeSetAudioOutput(aout);
        if (ret) {
            synchronized (this) {
                mAudioOutput = aout;
                /* The user forced an output, don't listen to audio plug events and let the user decide */
                mAudioDeviceFromUser = true;
                registerAudioPlug(false);
            }
        }
        return ret;
    }

    private boolean setAudioOutputDeviceInternal(String id, boolean fromUser) {
        final boolean ret = nativeSetAudioOutputDevice(id);
        if (ret) {
            synchronized (this) {
                mAudioOutputDevice = id;
                if (fromUser) {
                    /* The user forced a device, don't listen to audio plug events and let the user decide */
                    mAudioDeviceFromUser = true;
                    registerAudioPlug(false);
                }
            }
        }
        return ret;
    }

    /**
     * Configures an explicit audio output device.
     * Audio output will be moved to the device specified by the device identifier string.
     * <p>
     * Available devices for the "android_audiotrack" module (the default) are
     * "stereo": Up to 2 channels (compat mode).
     * "pcm": Up to 8 channels.
     * "encoded": Up to 8 channels, passthrough for every encodings if available.
     * "encoded:ENCODING_FLAGS_MASK": passthrough for every encodings specified by
     * ENCODING_FLAGS_MASK. This extra value is a long that contains binary-shifted
     * AudioFormat.ENCODING_* values.
     * <p>
     * Calling this method will disable the encoding detection (see {@link #setAudioOutput}).
     *
     * @return true on success.
     */
    public boolean setAudioOutputDevice(String id) {
        return setAudioOutputDeviceInternal(id, true);
    }

    /**
     * Get the full description of available titles.
     *
     * @return the list of titles
     */
    public Title[] getTitles() {
        return nativeGetTitles();
    }

    /**
     * Get the full description of available chapters.
     *
     * @param title index of the title (if -1, use the current title)
     * @return the list of Chapters for the title
     */
    public Chapter[] getChapters(int title) {
        return nativeGetChapters(title);
    }

    /**
     * Get the number of available video tracks.
     */
    public int getVideoTracksCount() {
        return nativeGetVideoTracksCount();
    }

    /**
     * Get the list of available video tracks.
     */
    public TrackDescription[] getVideoTracks() {
        return nativeGetVideoTracks();
    }

    /**
     * Get the current video track.
     *
     * @return the video track ID or -1 if no active input
     */
    public int getVideoTrack() {
        return nativeGetVideoTrack();
    }

    /**
     * Set the video track.
     *
     * @return true on success.
     */
    public boolean setVideoTrack(int index) {
        /* Don't activate a video track is surfaces are not ready */
        if (index == -1 || (mWindow.areViewsAttached() && !mWindow.areSurfacesWaiting())) {
            return nativeSetVideoTrack(index);
        } else
            return false;
    }

    /**
     * Set the enabled state of the video track
     *
     * @param enabled
     */
    public void setVideoTrackEnabled(boolean enabled) {
        if (!enabled) {
            setVideoTrack(-1);
        } else if (getVideoTrack() == -1) {
            final TrackDescription tracks[] = getVideoTracks();

            if (tracks != null) {
                for (TrackDescription track : tracks) {
                    if (track.id != -1) {
                        setVideoTrack(track.id);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Get the current video track
     */
    public Media.VideoTrack getCurrentVideoTrack() {
        if (getVideoTrack() == -1)
            return null;
        final int trackCount = mMedia.getTrackCount();
        for (int i = 0; i < trackCount; ++i) {
            final Media.Track track = mMedia.getTrack(i);
            if (track.type == Media.Track.Type.Video)
                return (Media.VideoTrack) track;
        }
        return null;
    }

    /**
     * Get the number of available audio tracks.
     */
    public int getAudioTracksCount() {
        return nativeGetAudioTracksCount();
    }

    /**
     * Get the list of available audio tracks.
     */
    public TrackDescription[] getAudioTracks() {
        return nativeGetAudioTracks();
    }

    /**
     * Get the current audio track.
     *
     * @return the audio track ID or -1 if no active input
     */
    public int getAudioTrack() {
        return nativeGetAudioTrack();
    }

    /**
     * Set the audio track.
     *
     * @return true on success.
     */
    public boolean setAudioTrack(int index) {
        return nativeSetAudioTrack(index);
    }

    /**
     * Get the current audio delay.
     *
     * @return delay in microseconds.
     */
    public long getAudioDelay() {
        return nativeGetAudioDelay();
    }

    /**
     * Set current audio delay. The audio delay will be reset to zero each time the media changes.
     *
     * @param delay in microseconds.
     * @return true on success.
     */
    public boolean setAudioDelay(long delay) {
        return nativeSetAudioDelay(delay);
    }

    /**
     * Get the number of available spu (subtitle) tracks.
     */
    public int getSpuTracksCount() {
        return nativeGetSpuTracksCount();
    }

    /**
     * Get the list of available spu (subtitle) tracks.
     */
    public TrackDescription[] getSpuTracks() {
        return nativeGetSpuTracks();
    }

    /**
     * Get the current spu (subtitle) track.
     *
     * @return the spu (subtitle) track ID or -1 if no active input
     */
    public int getSpuTrack() {
        return nativeGetSpuTrack();
    }

    /**
     * Set the spu (subtitle) track.
     *
     * @return true on success.
     */
    public boolean setSpuTrack(int index) {
        return nativeSetSpuTrack(index);
    }

    /**
     * Get the current spu (subtitle) delay.
     *
     * @return delay in microseconds.
     */
    public long getSpuDelay() {
        return nativeGetSpuDelay();
    }

    /**
     * Set current spu (subtitle) delay. The spu delay will be reset to zero each time the media changes.
     *
     * @param delay in microseconds.
     * @return true on success.
     */
    public boolean setSpuDelay(long delay) {
        return nativeSetSpuDelay(delay);
    }

    /**
     * Apply new equalizer settings to a media player.
     * <p>
     * The equalizer is first created by invoking {@link Equalizer#create()} or
     * {@link Equalizer#createFromPreset(int)}}.
     * <p>
     * It is possible to apply new equalizer settings to a media player whether the media
     * player is currently playing media or not.
     * <p>
     * Invoking this method will immediately apply the new equalizer settings to the audio
     * output of the currently playing media if there is any.
     * <p>
     * If there is no currently playing media, the new equalizer settings will be applied
     * later if and when new media is played.
     * <p>
     * Equalizer settings will automatically be applied to subsequently played media.
     * <p>
     * To disable the equalizer for a media player invoke this method passing null.
     *
     * @return true on success.
     */
    public boolean setEqualizer(Equalizer equalizer) {
        return nativeSetEqualizer(equalizer);
    }

    /**
     * Add a slave (or subtitle) to the current media player.
     *
     * @param type see {@link Media.Slave.Type}
     * @param uri  a valid RFC 2396 Uri
     * @return true on success.
     */
    public boolean addSlave(int type, Uri uri, boolean select) {
        return nativeAddSlave(type, VLCUtil.encodeVLCUri(uri), select);
    }

    /**
     * Add a slave (or subtitle) to the current media player.
     *
     * @param type see {@link Media.Slave.Type}
     * @param path a local path
     * @return true on success.
     */
    public boolean addSlave(int type, String path, boolean select) {
        return addSlave(type, Uri.fromFile(new File(path)), select);
    }

    /**
     * Get the current playback speed
     */
    public native float getRate();

    /**
     * Sets the speed of playback (1 being normal speed, 2 being twice as fast)
     *
     * @param rate
     */
    public native void setRate(float rate);

    /**
     * Returns true if any media is playing
     */
    public native boolean isPlaying();

    /**
     * Returns true if any media is seekable
     */
    public native boolean isSeekable();

    /**
     * Pauses any playing media
     */
    public native void pause();

    /**
     * Get player state.
     */
    public native int getPlayerState();

    /**
     * Gets volume as integer
     */
    public native int getVolume();

    /**
     * Sets volume as integer
     *
     * @param volume: Volume level passed as integer
     */
    public native int setVolume(int volume);

    /**
     * Gets the current movie time (in ms).
     *
     * @return the movie time (in ms), or -1 if there is no media.
     */
    public native long getTime();

    /**
     * Sets the movie time (in ms), if any media is being played.
     *
     * @param time: Time in ms.
     * @return the movie time (in ms), or -1 if there is no media.
     */
    public native long setTime(long time);

    /**
     * Gets the movie position.
     *
     * @return the movie position, or -1 for any error.
     */
    public native float getPosition();

    /**
     * Sets the movie position.
     *
     * @param pos: movie position.
     */
    public native void setPosition(float pos);

    /**
     * Gets current movie's length in ms.
     *
     * @return the movie length (in ms), or -1 if there is no media.
     */
    public native long getLength();

    public native int getTitle();

    public native void setTitle(int title);

    public native int getChapter();

    public native void setChapter(int chapter);

    public native int previousChapter();

    public native int nextChapter();

    public native void navigate(int navigate);

    public synchronized void setEventListener(EventListener listener) {
        super.setEventListener(listener);
    }

    @Override
    protected synchronized Event onEventNative(int eventType, long arg1, long arg2, float argf1) {
        switch (eventType) {
            case Event.MediaChanged:
            case Event.Stopped:
            case Event.EndReached:
            case Event.EncounteredError:
                mVoutCount = 0;
                notify();
            case Event.Opening:
            case Event.Buffering:
                return new Event(eventType, argf1);
            case Event.Playing:
            case Event.Paused:
                return new Event(eventType);
            case Event.TimeChanged:
                return new Event(eventType, arg1);
            case Event.PositionChanged:
                return new Event(eventType, argf1);
            case Event.Vout:
                mVoutCount = (int) arg1;
                notify();
                return new Event(eventType, arg1);
            case Event.ESAdded:
            case Event.ESDeleted:
            case Event.ESSelected:
                return new Event(eventType, arg1, arg2);
            case Event.SeekableChanged:
            case Event.PausableChanged:
                return new Event(eventType, arg1);
        }
        return null;
    }

    @Override
    protected void onReleaseNative() {
        registerAudioPlug(false);

        if (mMedia != null)
            mMedia.release();
        mVoutCount = 0;
        nativeRelease();
    }

    /* JNI */
    private native void nativeNewFromLibVlc(LibVLC libVLC, AWindow window);

    private native void nativeNewFromMedia(Media media, AWindow window);

    private native void nativeRelease();

    private native void nativeSetMedia(Media media);

    private native void nativePlay();

    private native void nativeStop();

    private native void nativeSetVideoTitleDisplay(int position, int timeout);

    private native float nativeGetScale();

    private native void nativeSetScale(float scale);

    private native String nativeGetAspectRatio();

    private native void nativeSetAspectRatio(String aspect);

    private native boolean nativeUpdateViewpoint(float yaw, float pitch, float roll, float fov, boolean absolute);

    private native boolean nativeSetAudioOutput(String aout);

    private native boolean nativeSetAudioOutputDevice(String id);

    private native Title[] nativeGetTitles();

    private native Chapter[] nativeGetChapters(int title);

    private native int nativeGetVideoTracksCount();

    private native TrackDescription[] nativeGetVideoTracks();

    private native int nativeGetVideoTrack();

    private native boolean nativeSetVideoTrack(int index);

    private native int nativeGetAudioTracksCount();

    private native TrackDescription[] nativeGetAudioTracks();

    private native int nativeGetAudioTrack();

    private native boolean nativeSetAudioTrack(int index);

    private native long nativeGetAudioDelay();

    private native boolean nativeSetAudioDelay(long delay);

    private native int nativeGetSpuTracksCount();

    private native TrackDescription[] nativeGetSpuTracks();

    private native int nativeGetSpuTrack();

    private native boolean nativeSetSpuTrack(int index);

    private native long nativeGetSpuDelay();

    private native boolean nativeSetSpuDelay(long delay);

    private native boolean nativeAddSlave(int type, String location, boolean select);

    private native boolean nativeSetEqualizer(Equalizer equalizer);

    public interface EventListener extends VLCEvent.Listener<Event> {
    }

    public static class Event extends VLCEvent {
        public static final int MediaChanged = 0x100;
        //public static final int NothingSpecial      = 0x101;
        public static final int Opening = 0x102;
        public static final int Buffering = 0x103;
        public static final int Playing = 0x104;
        public static final int Paused = 0x105;
        public static final int Stopped = 0x106;
        //public static final int Forward             = 0x107;
        //public static final int Backward            = 0x108;
        public static final int EndReached = 0x109;
        public static final int EncounteredError = 0x10a;
        public static final int TimeChanged = 0x10b;
        public static final int PositionChanged = 0x10c;
        public static final int SeekableChanged = 0x10d;
        public static final int PausableChanged = 0x10e;
        //public static final int TitleChanged        = 0x10f;
        //public static final int SnapshotTaken       = 0x110;
        //public static final int LengthChanged       = 0x111;
        public static final int Vout = 0x112;
        //public static final int ScrambledChanged    = 0x113;
        public static final int ESAdded = 0x114;
        public static final int ESDeleted = 0x115;
        public static final int ESSelected = 0x116;

        protected Event(int type) {
            super(type);
        }

        protected Event(int type, long arg1) {
            super(type, arg1);
        }

        protected Event(int type, long arg1, long arg2) {
            super(type, arg1, arg2);
        }

        protected Event(int type, float argf) {
            super(type, argf);
        }

        public long getTimeChanged() {
            return arg1;
        }

        public float getPositionChanged() {
            return argf1;
        }

        public int getVoutCount() {
            return (int) arg1;
        }

        public int getEsChangedType() {
            return (int) arg1;
        }

        public int getEsChangedID() {
            return (int) arg2;
        }

        public boolean getPausable() {
            return arg1 != 0;
        }

        public boolean getSeekable() {
            return arg1 != 0;
        }

        public float getBuffering() {
            return argf1;
        }
    }

    public static class Position {
        public static final int Disable = -1;
        public static final int Center = 0;
        public static final int Left = 1;
        public static final int Right = 2;
        public static final int Top = 3;
        public static final int TopLeft = 4;
        public static final int TopRight = 5;
        public static final int Bottom = 6;
        public static final int BottomLeft = 7;
        public static final int BottomRight = 8;
    }

    public static class Navigate {
        public static final int Activate = 0;
        public static final int Up = 1;
        public static final int Down = 2;
        public static final int Left = 3;
        public static final int Right = 4;
    }

    public static class Title {
        /**
         * duration in milliseconds
         */
        public final long duration;

        ;
        /**
         * title name
         */
        public final String name;
        /**
         * true if the title is a menu
         */
        private final int flags;

        public Title(long duration, String name, int flags) {
            this.duration = duration;
            this.name = name;
            this.flags = flags;
        }

        public boolean isMenu() {
            return (this.flags & Flags.MENU) != 0;
        }

        public boolean isInteractive() {
            return (this.flags & Flags.INTERACTIVE) != 0;
        }

        private static class Flags {
            public static final int MENU = 0x01;
            public static final int INTERACTIVE = 0x02;
        }
    }

    public static class Chapter {
        /**
         * time-offset of the chapter in milliseconds
         */
        public final long timeOffset;

        /**
         * duration of the chapter in milliseconds
         */
        public final long duration;

        /**
         * chapter name
         */
        public final String name;

        private Chapter(long timeOffset, long duration, String name) {
            this.timeOffset = timeOffset;
            this.duration = duration;
            this.name = name;
        }
    }

    public static class TrackDescription {
        public final int id;
        public final String name;

        private TrackDescription(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class Equalizer {
        @SuppressWarnings("unused") /* Used from JNI */
        private long mInstance;

        private Equalizer() {
            nativeNew();
        }

        private Equalizer(int index) {
            nativeNewFromPreset(index);
        }

        /**
         * Create a new default equalizer, with all frequency values zeroed.
         * The new equalizer can subsequently be applied to a media player by invoking
         * {@link MediaPlayer#setEqualizer}.
         */
        public static Equalizer create() {
            return new Equalizer();
        }

        /**
         * Create a new equalizer, with initial frequency values copied from an existing
         * preset.
         * The new equalizer can subsequently be applied to a media player by invoking
         * {@link MediaPlayer#setEqualizer}.
         */
        public static Equalizer createFromPreset(int index) {
            return new Equalizer(index);
        }

        /**
         * Get the number of equalizer presets.
         */
        public static int getPresetCount() {
            return nativeGetPresetCount();
        }

        /**
         * Get the name of a particular equalizer preset.
         * This name can be used, for example, to prepare a preset label or menu in a user
         * interface.
         *
         * @param index index of the preset, counting from zero.
         * @return preset name, or NULL if there is no such preset
         */

        public static String getPresetName(int index) {
            return nativeGetPresetName(index);
        }

        /**
         * Get the number of distinct frequency bands for an equalizer.
         */
        public static int getBandCount() {
            return nativeGetBandCount();
        }

        /**
         * Get a particular equalizer band frequency.
         * This value can be used, for example, to create a label for an equalizer band control
         * in a user interface.
         *
         * @param index index of the band, counting from zero.
         * @return equalizer band frequency (Hz), or -1 if there is no such band
         */
        public static float getBandFrequency(int index) {
            return nativeGetBandFrequency(index);
        }

        private static native int nativeGetPresetCount();

        private static native String nativeGetPresetName(int index);

        private static native int nativeGetBandCount();

        private static native float nativeGetBandFrequency(int index);

        @Override
        protected void finalize() throws Throwable {
            try {
                nativeRelease();
            } finally {
                super.finalize();
            }
        }

        /**
         * Get the current pre-amplification value from an equalizer.
         *
         * @return preamp value (Hz)
         */
        public float getPreAmp() {
            return nativeGetPreAmp();
        }

        /**
         * Set a new pre-amplification value for an equalizer.
         * The new equalizer settings are subsequently applied to a media player by invoking
         * {@link MediaPlayer#setEqualizer}.
         * The supplied amplification value will be clamped to the -20.0 to +20.0 range.
         *
         * @param preamp value (-20.0 to 20.0 Hz)
         * @return true on success.
         */
        public boolean setPreAmp(float preamp) {
            return nativeSetPreAmp(preamp);
        }

        /**
         * Get the amplification value for a particular equalizer frequency band.
         *
         * @param index counting from zero, of the frequency band to get.
         * @return amplification value (Hz); NaN if there is no such frequency band.
         */
        public float getAmp(int index) {
            return nativeGetAmp(index);
        }

        /**
         * Set a new amplification value for a particular equalizer frequency band.
         * The new equalizer settings are subsequently applied to a media player by invoking
         * {@link MediaPlayer#setEqualizer}.
         * The supplied amplification value will be clamped to the -20.0 to +20.0 range.
         *
         * @param index counting from zero, of the frequency band to set.
         * @param amp   amplification value (-20.0 to 20.0 Hz).
         *              \return true on success.
         */
        public boolean setAmp(int index, float amp) {
            return nativeSetAmp(index, amp);
        }

        private native void nativeNew();

        private native void nativeNewFromPreset(int index);

        private native void nativeRelease();

        private native float nativeGetPreAmp();

        private native boolean nativeSetPreAmp(float preamp);

        private native float nativeGetAmp(int index);

        private native boolean nativeSetAmp(int index, float amp);
    }
}
