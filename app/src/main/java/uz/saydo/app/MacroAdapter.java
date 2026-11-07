package uz.saydo.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uz.saydo.app.data.Macro;

/**
 * High-performance, zero-crash RecyclerView adapter for voice macros.
 * Operates on strongly-typed immutable Macro domain models and ID-based actions.
 */
final class MacroAdapter extends RecyclerView.Adapter<MacroAdapter.VH> {

    interface Listener {
        void onPlay(Macro macro);
        void onDelete(String macroId);
        void onEdit(Macro macro);
    }

    private final List<Macro> items = new ArrayList<>();
    private final Listener listener;

    MacroAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    void setData(List<Macro> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    private int indexOf(String macroId) {
        if (macroId == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (macroId.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    @Override
    public long getItemId(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position).id.hashCode();
        }
        return RecyclerView.NO_ID;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_macro, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (position < 0 || position >= items.size()) return;
        final Macro m = items.get(position);

        // Lock badge: the macro holds a PIN (stored encrypted, hidden from AI and export).
        h.title.setText(m.hasSensitiveSteps() ? "🔒 " + m.trigger : m.trigger);
        int actions = m.getGestureCount();
        double secs = m.duration / 1000.0;
        String metaText = actions + " actions · " + String.format(Locale.US, "%.1f", secs) + "s"
                + (m.locked ? " · from lock screen" : "")
                + (m.hasSensitiveSteps() ? " · PIN, private" : (McpGuard.isSensitive(m) ? " · sensitive" : ""));
        h.meta.setText(metaText);

        h.btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onPlay(m);
            }
        });

        h.btnDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onDelete(m.id);
            }
        });

        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onEdit(m);
                return true;
            }
        });
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title, meta;
        final ImageButton btnPlay, btnDel;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            btnPlay = v.findViewById(R.id.btnPlay);
            btnDel = v.findViewById(R.id.btnDel);
        }
    }
}
