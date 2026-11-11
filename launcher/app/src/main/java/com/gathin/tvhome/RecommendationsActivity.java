package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which recommendation rows Home shows: the master switch, and one switch per channel an app publishes. Home reads
 * the choices when it comes back (MainActivity.refreshRecommendations), so there is nothing to apply here.
 */
public final class RecommendationsActivity extends Activity {
    private static final int REQUEST_PERMISSION = 41;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView master, allow, status;
    private LinearLayout list;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_recommendations);
        master = findViewById(R.id.recMaster);
        allow = findViewById(R.id.recAllow);
        status = findViewById(R.id.recStatus);
        list = findViewById(R.id.recChannels);
        master.setOnClickListener(v -> {
            Recs.setEnabled(this, !Recs.enabled(this));
            refreshState();
        });
        allow.setOnClickListener(v -> requestPermissions(new String[]{Recs.PERMISSION}, REQUEST_PERMISSION));
        findViewById(R.id.recRefresh).setOnClickListener(v -> {
            int asked = Recs.requestPrograms(this);
            Toast.makeText(this, getString(R.string.rec_refreshed, asked), Toast.LENGTH_LONG).show();
            // The apps publish in their own time: read again after they had a moment.
            main.postDelayed(this::loadChannels, 3000);
        });
        master.requestFocus();
        refreshState();
        loadChannels();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.rec_permission_denied, Toast.LENGTH_LONG).show();
        }
        refreshState();
        loadChannels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private void refreshState() {
        master.setText(getString(R.string.rec_state, getString(Recs.enabled(this) ? R.string.on : R.string.off)));
        allow.setVisibility(Recs.hasPermission(this) ? View.GONE : View.VISIBLE);
    }

    private void loadChannels() {
        if (isDestroyed() || isFinishing()) return;
        if (!Recs.hasPermission(this)) {
            show(Collections.<Recs.Channel>emptyList());
            status.setText(R.string.rec_allow_hint);
            return;
        }
        status.setText(R.string.rec_loading);
        final Context app = getApplicationContext();
        try {
            executor.execute(() -> {
                final List<Recs.Channel> channels = Recs.load(app);
                main.post(() -> {
                    if (!isDestroyed() && !isFinishing()) show(channels);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The screen closed while the read was being queued.
        }
    }

    private void show(List<Recs.Channel> channels) {
        list.removeAllViews();
        // A channel with nothing in it right now has no row to show or hide; it appears here once it has cards.
        int listed = 0;
        for (final Recs.Channel channel : channels) {
            if (channel.programs.isEmpty()) continue;
            listed++;
            final TextView row = (TextView) getLayoutInflater().inflate(R.layout.item_rec_setting, list, false);
            row.setText(describe(channel));
            row.setOnClickListener(v -> {
                Recs.setChannelOn(this, channel.key, !Recs.channelOn(this, channel.key));
                row.setText(describe(channel));
            });
            list.addView(row);
        }
        status.setText(listed == 0 && Recs.hasPermission(this) ? getString(R.string.rec_none) : "");
    }

    private String describe(Recs.Channel channel) {
        String cards = getString(R.string.rec_channel_cards, channel.programs.size());
        String state = getString(Recs.channelOn(this, channel.key) ? R.string.on : R.string.off);
        return channel.heading() + "  \u00B7  " + cards + "  \u00B7  " + state;
    }
}
