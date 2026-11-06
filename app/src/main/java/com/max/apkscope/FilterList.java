package com.max.apkscope;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A searchable list: search box, live count, fast filtering over a precomputed lowercase key. */
final class FilterList<T> {

    interface Keyer<T> { String key(T item); }
    interface Binder<T> { void bind(T item, TextView title, TextView sub, TextView side); }
    interface Click<T> { void click(T item); }

    final LinearLayout view;
    final EditText search;
    private final TextView count;
    private final ListView list;
    private final List<T> all;
    private final String[] keys;
    private final List<T> shown = new ArrayList<>();
    private final String unit;
    private final BaseAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable applyTask = this::apply;

    FilterList(Context c, List<T> items, String hint, String unit, boolean monoTitle, int maxTitleLines,
               Keyer<T> keyer, Binder<T> binder, Click<T> click, View header) {
        this.all = items;
        this.unit = unit;
        this.keys = new String[items.size()];
        for (int i = 0; i < items.size(); i++) keys[i] = keyer.key(items.get(i)).toLowerCase(Locale.ROOT);

        view = Ui.vbox(c);
        search = Ui.search(c, hint);
        view.addView(search, Ui.lp(-1, -2));
        if (header != null) view.addView(header, Ui.margins(c, Ui.lp(-1, -2), 0, 10, 0, 0));
        count = Ui.text(c, "", 12, Ui.DIM, false);
        view.addView(count, Ui.margins(c, Ui.lp(-1, -2), 4, 10, 0, 4));

        list = new ListView(c);
        list.setDivider(new ColorDrawable(0x00000000));
        list.setDividerHeight(Ui.dp(c, 6));
        list.setSelector(new ColorDrawable(0x00000000));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 32));
        list.setFastScrollEnabled(true);
        view.addView(list, Ui.lp(-1, 0, 1f));

        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View convert, ViewGroup parent) {
                Row row;
                if (convert == null) {
                    row = new Row(c, monoTitle, maxTitleLines);
                    convert = row.root;
                    convert.setTag(row);
                } else {
                    row = (Row) convert.getTag();
                }
                row.sub.setText("");
                row.side.setText("");
                binder.bind(shown.get(p), row.title, row.sub, row.side);
                row.sub.setVisibility(row.sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
                row.side.setVisibility(row.side.getText().length() == 0 ? View.GONE : View.VISIBLE);
                return convert;
            }
        };
        list.setAdapter(adapter);
        if (click != null) list.setOnItemClickListener((parent, v, pos, id) -> click.click(shown.get(pos)));

        search.addTextChangedListener(Ui.watcher(() -> {
            handler.removeCallbacks(applyTask);
            handler.postDelayed(applyTask, 140);
        }));
        apply();
    }

    void setQuery(String q) {
        search.setText(q);
        search.setSelection(q.length());
        handler.removeCallbacks(applyTask);
        apply();
    }

    private void apply() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        if (q.isEmpty()) {
            shown.addAll(all);
        } else {
            for (int i = 0; i < keys.length; i++) if (keys[i].contains(q)) shown.add(all.get(i));
        }
        count.setText(String.format(Locale.US, "%,d of %,d %s", shown.size(), all.size(), unit));
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private static final class Row {
        final LinearLayout root;
        final TextView title, sub, side;

        Row(Context c, boolean mono, int maxLines) {
            root = Ui.hbox(c);
            root.setBackground(Ui.pressable(c, Ui.CARD, 14, Ui.STROKE));
            root.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
            LinearLayout col = Ui.vbox(c);
            title = mono ? Ui.mono(c, "", 13, Ui.TEXT) : Ui.text(c, "", 14, Ui.TEXT, true);
            title.setMaxLines(maxLines);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub = Ui.single(Ui.text(c, "", 11.5f, Ui.DIM, false));
            col.addView(title);
            col.addView(sub);
            root.addView(col, Ui.lp(0, -2, 1f));
            side = Ui.text(c, "", 11.5f, Ui.CYAN, true);
            root.addView(side, Ui.margins(c, Ui.lp(-2, -2), 10, 0, 0, 0));
        }
    }
}
