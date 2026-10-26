package com.hick.lancast;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native dashboard; intentionally uses no AndroidX or modern APIs. */
public final class LanCastActivity extends Activity {
    private TextView details;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { showStatus(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(30, 24, 30, 24);
        root.setBackgroundColor(Color.rgb(19, 32, 42));
        TextView title = new TextView(this);
        title.setText("车机局域网投屏 · Android 4.2");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        details = new TextView(this);
        details.setTextColor(Color.WHITE);
        details.setTextSize(17);
        details.setPadding(0, 24, 0, 22);
        root.addView(details);
        Button start = new Button(this);
        start.setText("启动 DLNA 接收");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startService(new Intent(LanCastActivity.this, CastService.class)); showStatus(); }
        });
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("停止接收");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopService(new Intent(LanCastActivity.this, CastService.class)); showStatus(); }
        });
        root.addView(stop);
        TextView foot = new TextView(this);
        foot.setText("将手机和车机连接同一个 Wi-Fi，进入视频应用的“投屏/DLNA”列表，选择 Car LAN Cast。\n支持可直连的视频网址；版权 DRM、Chromecast、Miracast 和 AirPlay 镜像需独立协议支持。\n请停车后测试，驾驶时不要观看视频。");
        foot.setTextColor(Color.LTGRAY);
        foot.setTextSize(14);
        foot.setPadding(0, 22, 0, 0);
        root.addView(foot);
        setContentView(root);
        startService(new Intent(this, CastService.class));
        showStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(updates, new IntentFilter(CastService.STATUS_ACTION));
        showStatus();
    }

    @Override protected void onPause() {
        unregisterReceiver(updates);
        super.onPause();
    }

    private void showStatus() {
        CastService current = CastService.getInstance();
        String server = current == null ? "未启动（或 Wi-Fi 未连接）" : current.getSummary();
        details.setText("DLNA 名称：Car LAN Cast\n当前状态：" + server);
    }
}
