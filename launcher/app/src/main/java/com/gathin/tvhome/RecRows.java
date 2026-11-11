package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.concurrent.Future;

/**
 * The recommendation rows under Home's app grid: one grid item per channel (its name over a sideways row of cards),
 * or a single "allow" button while Home may not read the TV provider yet. Moving between rows is MainActivity's
 * (onRecKey): the cards only report their keys, and swallow Left and Right at the ends of a row.
 */
final class RecRows {
    private RecRows() { }

    /** What the rows need from the screen that shows them: Up and Down on a card, and which card has focus. */
    interface Host {
        /** Up or Down on card index of the row at this position (or on the allow button). True when handled. */
        boolean onRecKey(int position, int index, int keyCode);

        /** A card of the row at this position took focus. */
        void onRecFocused(int position, int index);
    }

    /** Card height by artwork shape (dp): wide artwork is shorter than a poster, so rows stay about as tall. */
    private static final float WIDE_HEIGHT_DP = 108f, TALL_HEIGHT_DP = 160f;
    private static final float CARD_RADIUS_DP = 12f;
    private static final float FOCUS_SCALE = 1.07f;
    /** item_recommendation.xml around the card: 8dp above, the 8dp gap and one line of name, 4dp below, and zoom room. */
    private static final float LIST_EXTRA_DP = 46f;

    /**
     * Opens what a card points at. An intent that names no target of its own is kept inside the app that published
     * it; flags, selectors and everything else a URI can carry are dropped, so a card can only start an activity.
     */
    static void open(Context ctx, Recs.Program program) {
        try {
            Intent intent = Intent.parseUri(program.intentUri, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.getComponent() == null && intent.getPackage() == null) intent.setPackage(program.pkg);
            ctx.startActivity(intent);
        } catch (Exception e) {   // a bad URI, no activity for it, or one that is not exported
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, program.title.isEmpty() ? program.pkg : program.title),
                    Toast.LENGTH_SHORT).show();
        }
    }

    static RecyclerView.ViewHolder createRow(ViewGroup parent, Host listener) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_recommendations, parent, false);
        return new RowHolder(v, listener, 1f, false);
    }

    /**
     * A row that lives outside the grid (dock_channels shelf above the dock), with cards scaled by cardScale. Its
     * callbacks report position 0, since it has no adapter position.
     */
    static RowHolder createShelf(ViewGroup parent, Host listener, float cardScale) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_recommendations, parent, false);
        v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
        return new RowHolder(v, listener, cardScale, true);
    }

    static RecyclerView.ViewHolder createPrompt(ViewGroup parent, Host listener) {
        final Context ctx = parent.getContext();
        View row = LayoutInflater.from(ctx).inflate(R.layout.row_rec_prompt, parent, false);
        final RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(row) { };
        View button = row.findViewById(R.id.btnRecPrompt);
        button.setOnClickListener(v -> ctx.startActivity(new Intent(ctx, RecommendationsActivity.class)));
        button.setOnFocusChangeListener((v, focused) -> {
            int pos = holder.getBindingAdapterPosition();
            if (focused && pos != RecyclerView.NO_POSITION) listener.onRecFocused(pos, 0);
        });
        button.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (key == KeyEvent.KEYCODE_DPAD_UP && pos != RecyclerView.NO_POSITION) return listener.onRecKey(pos, 0, key);
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        return holder;
    }

    /** One channel: the title and the sideways list of its cards. */
    static final class RowHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final RecyclerView list;
        private final CardAdapter cards;
        private final float cardScale;
        private final boolean standalone;

        RowHolder(View v, Host listener, float cardScale, boolean standalone) {
            super(v);
            this.cardScale = cardScale;
            this.standalone = standalone;
            title = v.findViewById(R.id.recTitle);
            list = v.findViewById(R.id.recList);
            list.setLayoutManager(new LinearLayoutManager(v.getContext(), RecyclerView.HORIZONTAL, false));
            list.setItemAnimator(null);
            cards = new CardAdapter(this, listener);
            list.setAdapter(cards);
        }

        void bind(Recs.Channel channel) {
            title.setText(channel.heading());
            cards.setChannel(channel);
        }

        /** The card at this index of the row, or null when it is not laid out. */
        View cardAt(int index) {
            RecyclerView.ViewHolder card = list.findViewHolderForAdapterPosition(index);
            return card == null ? null : card.itemView.findViewById(R.id.recCard);
        }

        /** Position in the grid; a shelf outside the grid is always 0. */
        int rowPosition() {
            int pos = getBindingAdapterPosition();
            return standalone && pos == RecyclerView.NO_POSITION ? 0 : pos;
        }
    }

    private static final class CardAdapter extends RecyclerView.Adapter<CardAdapter.CardHolder> {
        private final RowHolder row;
        private final Host listener;
        private Recs.Channel channel;
        private String signature = "";
        private int cardW, cardH;

        CardAdapter(RowHolder row, Host listener) {
            this.row = row;
            this.listener = listener;
            setHasStableIds(true);
        }

        void setChannel(Recs.Channel next) {
            String sig = next.signature();
            if (sig.equals(signature)) return;   // the same cards: keep the scroll position and focus
            signature = sig;
            channel = next;
            float density = row.itemView.getResources().getDisplayMetrics().density;
            float aspect = next.programs.isEmpty() ? 16f / 9f : next.programs.get(0).aspect;
            cardH = Math.round((aspect >= 1f ? WIDE_HEIGHT_DP : TALL_HEIGHT_DP) * row.cardScale * density);
            cardW = Math.round(cardH * aspect);
            // The list is as tall as a card plus its name, padding and room for the focus zoom.
            ViewGroup.LayoutParams listLp = row.list.getLayoutParams();
            listLp.height = cardH + Math.round(LIST_EXTRA_DP * density);
            row.list.setLayoutParams(listLp);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() {
            return channel == null ? 0 : channel.programs.size();
        }

        @Override public long getItemId(int position) {
            return channel.programs.get(position).id;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recommendation, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            h.bind(channel.programs.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull CardHolder h) {
            h.release();
        }

        final class CardHolder extends RecyclerView.ViewHolder {
            private final FrameLayout card;
            private final ImageView image;
            private final TextView fallback, name;
            private Future<?> loading;
            private String wanted = "";

            CardHolder(View v) {
                super(v);
                card = v.findViewById(R.id.recCard);
                image = v.findViewById(R.id.recImage);
                fallback = v.findViewById(R.id.recFallback);
                name = v.findViewById(R.id.recName);
                final float density = v.getResources().getDisplayMetrics().density;
                card.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), CARD_RADIUS_DP * density);
                    }
                });
                card.setClipToOutline(true);
                card.setOnFocusChangeListener((view, focused) -> {
                    view.animate().cancel();
                    view.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f).setDuration(150).start();
                    name.setAlpha(focused ? 1f : 0.7f);
                    int rowPos = row.rowPosition();
                    if (focused && rowPos != RecyclerView.NO_POSITION) {
                        int index = getBindingAdapterPosition();
                        listener.onRecFocused(rowPos, index == RecyclerView.NO_POSITION ? 0 : index);
                    }
                });
                card.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int index = getBindingAdapterPosition();
                    int rowPos = row.rowPosition();
                    if (index == RecyclerView.NO_POSITION || rowPos == RecyclerView.NO_POSITION) return false;
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_LEFT: return index == 0;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: return index >= getItemCount() - 1;
                        case KeyEvent.KEYCODE_DPAD_UP:
                        case KeyEvent.KEYCODE_DPAD_DOWN: return listener.onRecKey(rowPos, index, keyCode);
                        default: return false;
                    }
                });
                card.setOnLongClickListener(view -> {
                    view.getContext().startActivity(new Intent(view.getContext(), RecommendationsActivity.class));
                    return true;
                });
            }

            void bind(final Recs.Program program) {
                ViewGroup.LayoutParams cardLp = card.getLayoutParams();
                cardLp.width = cardW;
                cardLp.height = cardH;
                card.setLayoutParams(cardLp);
                ViewGroup.LayoutParams nameLp = name.getLayoutParams();
                nameLp.width = cardW;
                name.setLayoutParams(nameLp);
                name.setText(program.title);
                name.setAlpha(card.isFocused() ? 1f : 0.7f);
                card.setOnClickListener(v -> open(v.getContext(), program));
                release();
                wanted = program.poster;
                fallback.setText(program.title);
                Bitmap ready = RecImages.cached(program.poster);
                if (ready != null) {
                    show(ready);
                } else if (program.poster.isEmpty()) {
                    show(null);
                } else {
                    image.setImageDrawable(null);
                    fallback.setVisibility(View.VISIBLE);
                    final String uri = program.poster;
                    loading = RecImages.load(itemView.getContext(), uri, cardW, bitmap -> {
                        // The card may have been rebound to another program while the picture came in.
                        if (uri.equals(wanted)) show(bitmap);
                    });
                }
            }

            private void show(Bitmap bitmap) {
                image.setImageBitmap(bitmap);
                fallback.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
            }

            void release() {
                if (loading != null) loading.cancel(true);
                loading = null;
                wanted = "";
            }
        }
    }
}
