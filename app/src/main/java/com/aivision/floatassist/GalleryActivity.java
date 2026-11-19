package com.aivision.floatassist;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** AI 截图库：查看单击存下来的截图与文字 */
public class GalleryActivity extends Activity {

    private final List<ShotStore.Shot> shots = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView emptyView;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, 0);

        TextView title = new TextView(this);
        title.setText("AI 截图库");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(0xFF101828);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("单击悬浮球，当前屏幕就会存到这里，点开可以查看或复制文字。");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(0xFF667085);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 6);
        root.addView(sub, subLp);

        emptyView = new TextView(this);
        emptyView.setText("还没有截图\n开启悬浮球后单击一下即可存图");
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyView.setTextColor(0xFF98A2B3);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(emptyView, emptyLp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 10));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(list, listLp);

        adapter = new Adapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            ShotStore.Shot shot = shots.get(position);
            showPreview(shot);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            ShotStore.Shot shot = shots.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除这张截图？")
                    .setPositiveButton("删除", (d, w) -> {
                        ShotStore.delete(this, shot);
                        reload();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        shots.clear();
        shots.addAll(ShotStore.list(this));
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(shots.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showPreview(ShotStore.Shot shot) {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF15171D);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        ImageView image = new ImageView(this);
        image.setImageBitmap(load(shot, false));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 320)));

        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(shot.ocr == null || shot.ocr.trim().isEmpty() ? "（这张截图里没有识别到文字）" : shot.ocr);
        text.setTextColor(0xFFE9ECF2);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTextIsSelectable(true);
        text.setLineSpacing(Ui.dp(this, 3), 1f);
        scroll.addView(text);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollLp.topMargin = Ui.dp(this, 12);
        root.addView(scroll, scrollLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Ui.dp(this, 12);

        TextView copy = darkChip("复制文字");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("截图文字", text.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy);

        TextView close = darkChip("关闭");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        close.setOnClickListener(v -> dialog.dismiss());
        actions.addView(close, lp);

        root.addView(actions, actionsLp);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    private TextView darkChip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(Ui.dp(this, 100));
        tv.setBackground(bg);
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        tv.setClickable(true);
        return tv;
    }

    private Bitmap load(ShotStore.Shot shot, boolean small) {
        File f = ShotStore.fileOf(this, shot);
        String key = f.getAbsolutePath() + (small ? "_s" : "_l");
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        if (small) {
            int want = 240;
            int sample = 1;
            while (bounds.outWidth / sample > want * 2) sample *= 2;
            opts.inSampleSize = sample;
        }
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        if (bmp != null) cache.put(key, bmp);
        return bmp;
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shots.size();
        }

        @Override
        public Object getItem(int position) {
            return shots.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(GalleryActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(GalleryActivity.this, 12), Ui.dp(GalleryActivity.this, 10),
                        Ui.dp(GalleryActivity.this, 12), Ui.dp(GalleryActivity.this, 10));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.WHITE);
                bg.setCornerRadius(Ui.dp(GalleryActivity.this, 14));
                bg.setStroke(Ui.dp(GalleryActivity.this, 1), 0xFFE4E7EC);
                row.setBackground(bg);

                ImageView thumb = new ImageView(GalleryActivity.this);
                thumb.setId(View.generateViewId());
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable thumbBg = new GradientDrawable();
                thumbBg.setColor(0xFFF2F4F7);
                thumbBg.setCornerRadius(Ui.dp(GalleryActivity.this, 8));
                thumb.setBackground(thumbBg);
                thumb.setClipToOutline(true);
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(GalleryActivity.this, 64),
                        Ui.dp(GalleryActivity.this, 76)));

                LinearLayout texts = new LinearLayout(GalleryActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                textsLp.leftMargin = Ui.dp(GalleryActivity.this, 12);

                TextView one = new TextView(GalleryActivity.this);
                one.setId(View.generateViewId());
                one.setTextColor(0xFF101828);
                one.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                texts.addView(one);

                TextView two = new TextView(GalleryActivity.this);
                two.setId(View.generateViewId());
                two.setTextColor(0xFF667085);
                two.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                two.setSingleLine(true);
                LinearLayout.LayoutParams twoLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                twoLp.topMargin = Ui.dp(GalleryActivity.this, 3);
                texts.addView(two, twoLp);

                row.addView(texts, textsLp);
            }

            ShotStore.Shot shot = shots.get(position);
            ImageView thumb = (ImageView) row.getChildAt(0);
            thumb.setImageBitmap(load(shot, true));
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView one = (TextView) texts.getChildAt(0);
            TextView two = (TextView) texts.getChildAt(1);
            one.setText(ShotStore.describe(GalleryActivity.this, shot).split(" · ")[0]);
            two.setText(shot.ocr == null || shot.ocr.trim().isEmpty()
                    ? "暂无文字" : shot.ocr.replaceAll("\\s+", " ").trim());
            return row;
        }
    }
}
