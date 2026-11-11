package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The recommendation rows as a page: where Down from the scrolling dock (single_row) leads, since that layout has no
 * grid under the dock for the rows to sit in. Up from the first row, or Back, returns to the dock.
 */
public final class RecsPageActivity extends Activity implements RecRows.Host {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Recs.Channel> rows = new ArrayList<>();
    private RecyclerView list;
    private TextView status;
    private String signature = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_recs_page);
        status = findViewById(R.id.recsPageStatus);
        list = findViewById(R.id.recsPage);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setAdapter(new PageAdapter());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void load() {
        if (!Recs.enabled(this) || !Recs.hasPermission(this)) {
            apply(Collections.<Recs.Channel>emptyList());
            return;
        }
        final Context app = getApplicationContext();
        try {
            executor.execute(() -> {
                final List<Recs.Channel> shown = Recs.visible(app, Recs.load(app));
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) apply(shown);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The page closed while the read was being queued.
        }
    }

    private void apply(List<Recs.Channel> next) {
        StringBuilder sb = new StringBuilder();
        for (Recs.Channel c : next) sb.append('\n').append(c.signature());
        String sig = sb.toString();
        View focus = getCurrentFocus();
        // Never swap the rows under the card the viewer is on; the next resume catches up.
        if (sig.equals(signature) || (focus != null && focus.getId() == R.id.recCard)) return;
        signature = sig;
        rows.clear();
        rows.addAll(next);
        list.getAdapter().notifyDataSetChanged();
        if (rows.isEmpty()) {
            status.setText(!Recs.enabled(this) ? R.string.rec_page_off
                    : !Recs.hasPermission(this) ? R.string.rec_allow_hint : R.string.rec_none);
        } else {
            status.setText("");
            list.post(() -> focusRow(0, 0, 0));
        }
    }

    @Override
    public boolean onRecKey(int position, int index, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (position + 1 < rows.size()) focusRow(position + 1, index, 0);
        } else if (position == 0) {
            finish();   // Up from the first row: back to the dock
        } else {
            focusRow(position - 1, index, 0);
        }
        return true;
    }

    @Override
    public void onRecFocused(int position, int index) {
    }

    /** Focuses card index of the row at position, scrolling the page or the row to it first when it is off screen. */
    private void focusRow(final int position, final int index, final int attempt) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            if (attempt < 3) {
                list.scrollToPosition(position);
                list.post(() -> focusRow(position, index, attempt + 1));
            }
            return;
        }
        RecyclerView cards = holder.itemView.findViewById(R.id.recList);
        int count = cards.getAdapter() == null ? 0 : cards.getAdapter().getItemCount();
        if (count == 0) return;
        int target = Math.max(0, Math.min(index, count - 1));
        RecyclerView.ViewHolder card = cards.findViewHolderForAdapterPosition(target);
        if (card == null) {
            if (attempt < 3) {
                cards.scrollToPosition(target);
                cards.post(() -> focusRow(position, index, attempt + 1));
            }
            return;
        }
        card.itemView.findViewById(R.id.recCard).requestFocus();
    }

    private final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        PageAdapter() {
            setHasStableIds(true);
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        @Override public long getItemId(int position) {
            return rows.get(position).uniqueKey.hashCode();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return RecRows.createRow(parent, RecsPageActivity.this);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((RecRows.RowHolder) holder).bind(rows.get(position));
        }
    }
}
