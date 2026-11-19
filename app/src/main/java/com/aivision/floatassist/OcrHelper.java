package com.aivision.floatassist;

import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/** 本地 OCR（ML Kit 中文识别，支持中英文，离线可用） */
public final class OcrHelper {

    public interface Callback {
        void onText(String text);
    }

    private static TextRecognizer recognizer;

    private OcrHelper() {
    }

    private static synchronized TextRecognizer get() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        return recognizer;
    }

    public static void recognize(Bitmap src, Callback cb) {
        if (src == null) {
            cb.onText("");
            return;
        }
        Bitmap scaled = shrink(src, 1600);
        InputImage image = InputImage.fromBitmap(scaled, 0);
        get().process(image)
                .addOnSuccessListener(result -> cb.onText(join(result)))
                .addOnFailureListener(e -> cb.onText(""));
    }

    private static String join(Text text) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(block.getText());
        }
        return sb.toString().trim();
    }

    private static Bitmap shrink(Bitmap src, int max) {
        int w = src.getWidth();
        int h = src.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return src;
        float ratio = max / (float) big;
        return Bitmap.createScaledBitmap(src, Math.round(w * ratio), Math.round(h * ratio), true);
    }
}
