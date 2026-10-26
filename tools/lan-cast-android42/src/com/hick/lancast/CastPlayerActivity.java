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
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import com.shilapi.xcertplay.diagnostic.HeadUnitDiagnostics;
import com.shilapi.xcertplay.diagnostic.HeadUnitAutoUploader;

/** Uses Android's platform decoders, without pretending unsupported DRM formats work. */
public final class CastPlayerActivity extends Activity {
    private static final String TAG = "CarLanCast";
    private VideoView video;
    private TextView error;
    private Button reportBlackScreen;
    private volatile boolean firstFrameConfirmed;
    private volatile boolean prepared;
    private volatile boolean blackScreenLogged;
    static volatile int positionMs;
    static volatile int durationMs;
    private final Handler handler = new Handler();
    private final Runnable firstFrameWatchdog = new Runnable() {
        @Override public void run() {
            if (isFinishing() || !prepared || firstFrameConfirmed || blackScreenLogged) return;
            blackScreenLogged = true;
            int time = 0;
            try { time = video.getCurrentPosition(); } catch (Exception ignored) { }
            HeadUnitDiagnostics.flagIncident("DLNA: decoder prepared but no first-frame " +
                "confirmation in 12 seconds; currentPosition=" + time +
                "ms; platform MediaPlayer may omit rendering callback");
            HeadUnitAutoUploader.schedule(CastPlayerActivity.this, "DLNA black-screen watchdog");
            error.setText("已自动上报投屏画面诊断；若画面仍黑屏，请检查车机解码能力。");
            error.setVisibility(View.VISIBLE);
        }
    };
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
        HeadUnitDiagnostics.event("CAST_PLAYER_OPEN", "Android video player activity created");
        // VideoView outlives the receiving dashboard. Without its own keep-awake
        // flag, the screen can doze between Bilibili playback and the next cast,
        // and the head unit stops answering new DLNA discovery/control sockets.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        reportBlackScreen = new Button(this);
        reportBlackScreen.setText("报告黑屏 / 立即上传");
        reportBlackScreen.setTextSize(13);
        FrameLayout.LayoutParams reportLayout = new FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP | Gravity.RIGHT);
        reportLayout.setMargins(8, 8, 8, 0);
        layout.addView(reportBlackScreen, reportLayout);
        reportBlackScreen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int time = 0;
                int duration = 0;
                try {
                    time = video.getCurrentPosition();
                    duration = video.getDuration();
                } catch (Exception ignored) { }
                HeadUnitDiagnostics.event("BLACK_SCREEN_BUTTON",
                    "prepared=" + prepared + " firstFrameConfirmed=" + firstFrameConfirmed +
                    " positionMs=" + time + " durationMs=" + duration);
                reportBlackScreen.setEnabled(false);
                error.setText("正在自动查找 Mac 并上传黑屏诊断，请保留当前画面…");
                error.setVisibility(View.VISIBLE);
                HeadUnitAutoUploader.uploadManual(CastPlayerActivity.this,
                    "LAN Cast player black-screen button",
                    new HeadUnitAutoUploader.UploadCallback() {
                        @Override public void onFinished(boolean success, String status) {
                            reportBlackScreen.setEnabled(true);
                            error.setText(status);
                            error.setVisibility(View.VISIBLE);
                        }
                    });
            }
        });
        setContentView(layout);
        video.setMediaController(new MediaController(this));
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "DLNA Android player error what=" + what + " extra=" + extra);
                HeadUnitDiagnostics.flagIncident("DLNA player error what=" + what + " extra=" + extra);
                HeadUnitAutoUploader.schedule(CastPlayerActivity.this, "DLNA decoder failure");
                error.setText("当前车机解码器无法播放该媒体 (" + what + ", " + extra + ")");
                error.setVisibility(View.VISIBLE);
                CastService.updateTransport("STOPPED");
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                Log.i(TAG, "DLNA Android player completed");
                HeadUnitDiagnostics.event("CAST_PLAYER_DONE", "video playback completed normally");
                CastService.updateTransport("STOPPED");
                // Return to the receiver dashboard, whose keep-screen-on policy
                // preserves discoverability between successive cast sessions.
                finish();
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                Log.i(TAG, "DLNA Android player prepared successfully");
                prepared = true;
                HeadUnitDiagnostics.event("CAST_PLAYER_PREPARED", "MediaPlayer onPrepared");
                HeadUnitDiagnostics.event("CAST_VIDEO_SIZE", "media dimensions=" +
                    mp.getVideoWidth() + "x" + mp.getVideoHeight());
                handler.removeCallbacks(firstFrameWatchdog);
                handler.postDelayed(firstFrameWatchdog, 12000L);
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
                HeadUnitDiagnostics.event("CAST_VIDEO_INFO", "MediaPlayer info=" + what + "/" + extra);
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrameConfirmed = true;
                    handler.removeCallbacks(firstFrameWatchdog);
                    HeadUnitDiagnostics.event("CAST_FIRST_FRAME", "Android decoder reported rendered video");
                }
                return false;
            }
        });
        String url = getIntent().getStringExtra("mediaUri");
        if (url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtsp://"))) {
            Uri media = Uri.parse(url);
            HeadUnitDiagnostics.event("CAST_URI", "scheme=" + media.getScheme() +
                " host=" + media.getHost() + " length=" + url.length());
            positionMs = 0;
            durationMs = 0;
            video.setVideoURI(Uri.parse(url));
            video.requestFocus();
            video.start();
        } else {
            HeadUnitDiagnostics.flagIncident("DLNA receiver passed missing/unsupported media URI");
            HeadUnitAutoUploader.schedule(this, "Missing DLNA media URL");
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
        HeadUnitDiagnostics.event("CAST_PLAYER_PAUSE", "player backgrounded");
        unregisterReceiver(commands);
        super.onPause();
    }

    @Override protected void onDestroy() {
        HeadUnitDiagnostics.event("CAST_PLAYER_DESTROY", "player closed");
        handler.removeCallbacks(firstFrameWatchdog);
        handler.removeCallbacks(trackPosition);
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
