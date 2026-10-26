package com.hick.lancast;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Uses Android's platform decoders, without pretending unsupported DRM formats work. */
public final class CastPlayerActivity extends Activity {
    private VideoView video;
    private TextView error;
    static volatile int positionMs;
    static volatile int durationMs;
    private final Handler handler = new Handler();
    private final Runnable trackPosition = new Runnable() {
        @Override public void run() {
            if (video != null) {
                try {
                    positionMs = video.getCurrentPosition();
                    durationMs = Math.max(0, video.getDuration());
                } catch (Exception ignored) { }
            }
            handler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("command");
            if ("pause".equals(action)) {
                if (video.isPlaying()) video.pause();
            } else if ("play".equals(action)) {
                if (!video.isPlaying()) video.start();
            } else if ("stop".equals(action)) {
                video.stopPlayback();
                finish();
            } else if ("seek".equals(action)) {
                video.seekTo(intent.getIntExtra("positionMs", 0));
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        layout.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        error = new TextView(this);
        error.setTextColor(Color.WHITE);
        error.setBackgroundColor(0x99000000);
        error.setTextSize(15);
        error.setPadding(12, 12, 12, 12);
        error.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        layout.addView(error, errorParams);
        setContentView(layout);
        video.setMediaController(new MediaController(this));
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                error.setText("当前车机解码器无法播放该媒体 (" + what + ", " + extra + ")");
                error.setVisibility(View.VISIBLE);
                CastService.updateTransport("STOPPED");
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) { CastService.updateTransport("STOPPED"); }
        });
        String url = getIntent().getStringExtra("mediaUri");
        if (url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtsp://"))) {
            positionMs = 0;
            durationMs = 0;
            video.setVideoURI(Uri.parse(url));
            video.requestFocus();
            video.start();
        } else {
            error.setText("未提供可用的网络媒体地址");
            error.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(trackPosition, 1000);
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(commands, new IntentFilter(CastService.PLAYER_ACTION));
    }

    @Override protected void onPause() {
        unregisterReceiver(commands);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(trackPosition);
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
