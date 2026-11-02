package uz.saydo.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Shortcutlar ro'yxati. */
final class MacroAdapter extends RecyclerView.Adapter<MacroAdapter.VH> {

    interface Listener {
        void onPlay(int index);
        void onDelete(int index);
    }

    private JSONArray data = new JSONArray();
    private final Listener listener;
    private int armedDelete = -1;

    MacroAdapter(Listener listener) {
        this.listener = listener;
    }

    void setData(JSONArray arr) {
        data = arr != null ? arr : new JSONArray();
        armedDelete = -1;
        notifyDataSetChanged();
    }

    void armDelete(int index) {
        int prev = armedDelete;
        armedDelete = index;
        if (prev >= 0) notifyItemChanged(prev);
        if (index >= 0) notifyItemChanged(index);
    }

    int getArmedDelete() {
        return armedDelete;
    }

    @Override
    public int getItemCount() {
        return data.length();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_macro, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JSONObject m = data.optJSONObject(position);
        if (m == null) return;
        String trigger = m.optString("trigger", "?");
        JSONArray g = m.optJSONArray("gestures");
        int n = g != null ? g.length() : 0;
        double secs = m.optLong("dur", 0) / 1000.0;
        h.title.setText(trigger);
        h.meta.setText(n + " actions · " + String.format(java.util.Locale.US, "%.1f", secs) + "s");

        final int pos = position;
        h.btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onPlay(pos);
            }
        });
        h.btnDel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onDelete(pos);
            }
        });
        h.btnDel.setAlpha(armedDelete == position ? 1f : 0.85f);
        h.btnDel.setScaleX(armedDelete == position ? 1.08f : 1f);
        h.btnDel.setScaleY(armedDelete == position ? 1.08f : 1f);
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
