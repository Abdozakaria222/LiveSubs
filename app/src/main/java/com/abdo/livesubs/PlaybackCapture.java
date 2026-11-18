package com.abdo.livesubs;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;

/** Android 10+ only: records what other apps are playing. Kept separate so older devices never load it. */
@TargetApi(29)
final class PlaybackCapture {
    static final class Result {
        MediaProjection projection;
        AudioRecord recorder;
    }

    static Result create(Context ctx, int resultCode, Intent data, AudioFormat fmt, int buf,
                         Handler main, Runnable onStop) {
        MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection p = mpm.getMediaProjection(resultCode, data);
        if (p == null) throw new IllegalStateException("no projection");
        p.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { onStop.run(); }
        }, main);
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        Result r = new Result();
        r.projection = p;
        r.recorder = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(buf)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        return r;
    }
}
