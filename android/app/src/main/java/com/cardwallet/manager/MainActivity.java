package com.cardwallet.manager;

import android.animation.ValueAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    private TextRecognizer offlineRecognizer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Window window = getWindow();
        // Set Activity windowBackground to #F8F9FA (light white) to prevent any purple flash during WebView load or transition
        window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#F8F9FA")));
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        // Full-screen edge-to-edge coverage without camera cutout avoidance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.setAttributes(lp);
        }

        super.onCreate(savedInstanceState);

        try {
            Method setDurationScale = ValueAnimator.class.getMethod("setDurationScale", float.class);
            setDurationScale.invoke(null, 1.0f);
        } catch (Throwable ignored) {}

        try {
            offlineRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        } catch (Throwable ignored) {}

        if (this.bridge != null && this.bridge.getWebView() != null) {
            WebView webView = this.bridge.getWebView();

            // Match WebView background to #F8F9FA and force hardware-accelerated rendering for 1:1 crisp skin images
            webView.setBackgroundColor(Color.parseColor("#F8F9FA"));
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            WebSettings ws = webView.getSettings();
            if (ws != null) {
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                ws.setLoadsImagesAutomatically(true);
            }

            // 1. Native Back, System Animation Scale & Immersive Fullscreen Bridge
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void moveTaskToBack() {
                    runOnUiThread(() -> MainActivity.this.moveTaskToBack(true));
                }

                @JavascriptInterface
                public void setImmersiveFullscreen(final boolean enabled) {
                    runOnUiThread(() -> applySystemImmersiveMode(enabled));
                }

                @JavascriptInterface
                public float getSystemAnimationScale() {
                    try {
                        return Settings.Global.getFloat(
                            getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE,
                            1.0f
                        );
                    } catch (Exception e) {
                        return 1.0f;
                    }
                }
            }, "AndroidBackBridge");

            // 2. On-Device Offline OCR Bridge (Bank Cards, WeChat/Alipay Bills & Receipts)
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void recognizeImageOffline(String dataUrl, String reqId) {
                    try {
                        String base64Data = dataUrl;
                        int commaIdx = dataUrl.indexOf(',');
                        if (commaIdx != -1) {
                            base64Data = dataUrl.substring(commaIdx + 1);
                        }
                        byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                        if (bitmap == null || offlineRecognizer == null) {
                            sendOcrCallback(reqId, "", "Decode failed");
                            return;
                        }
                        InputImage image = InputImage.fromBitmap(bitmap, 0);
                        offlineRecognizer.process(image)
                            .addOnSuccessListener(visionText -> {
                                String resultText = visionText != null ? visionText.getText() : "";
                                sendOcrCallback(reqId, resultText, "");
                            })
                            .addOnFailureListener(e -> {
                                sendOcrCallback(reqId, "", e.getMessage());
                            });
                    } catch (Exception ex) {
                        sendOcrCallback(reqId, "", ex.getMessage());
                    }
                }
            }, "AndroidOcrBridge");

            // 3. Native File Export & Share Bridge (Downloads Directory Storage)
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public boolean saveFileToDownloads(String fileName, String mimeType, String content) {
                    try {
                        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                            values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
                            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                            if (uri != null) {
                                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                    if (os != null) {
                                        os.write(bytes);
                                        os.flush();
                                        return true;
                                    }
                                }
                            }
                        } else {
                            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            if (!dir.exists()) {
                                dir.mkdirs();
                            }
                            File file = new File(dir, fileName);
                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                fos.write(bytes);
                                fos.flush();
                                return true;
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    return false;
                }

                @JavascriptInterface
                public void shareBackupText(String title, String content) {
                    runOnUiThread(() -> {
                        try {
                            Intent sendIntent = new Intent();
                            sendIntent.setAction(Intent.ACTION_SEND);
                            sendIntent.putExtra(Intent.EXTRA_TEXT, content);
                            sendIntent.setType("text/plain");
                            Intent shareIntent = Intent.createChooser(sendIntent, title);
                            startActivity(shareIntent);
                        } catch (Throwable ignored) {}
                    });
                }
            }, "AndroidFileBridge");

            // 4. High-Resolution Skin Image Bridge (No secondary downsampling or lossy re-compression)
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String processOriginalSkinImage(String dataUrl, int screenW, int screenH) {
                    try {
                        if (dataUrl == null || dataUrl.isEmpty()) return "";
                        String base64Data = dataUrl;
                        int commaIdx = dataUrl.indexOf(',');
                        if (commaIdx != -1) {
                            base64Data = dataUrl.substring(commaIdx + 1);
                        }
                        byte[] rawBytes = Base64.decode(base64Data, Base64.DEFAULT);

                        // Read dimensions first without allocating pixels
                        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
                        boundsOpts.inJustDecodeBounds = true;
                        boundsOpts.inScaled = false;
                        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, boundsOpts);

                        int imgW = boundsOpts.outWidth;
                        int imgH = boundsOpts.outHeight;
                        int maxLimit = Math.max(Math.max(screenW, screenH) * 2, 3200);

                        // If within screen physical limits, return 100% untouched original dataUrl without any re-compression!
                        if (imgW <= 0 || imgH <= 0 || (imgW <= maxLimit && imgH <= maxLimit)) {
                            return dataUrl;
                        }

                        // Only proportionally scale when image far exceeds screen resolution, using ARGB_8888 & lossless PNG
                        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                        decodeOpts.inScaled = false;
                        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        decodeOpts.inDither = false;
                        Bitmap rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, decodeOpts);
                        if (rawBitmap == null) return dataUrl;

                        float ratio = Math.min((float) maxLimit / imgW, (float) maxLimit / imgH);
                        int targetW = Math.max(1, Math.round(imgW * ratio));
                        int targetH = Math.max(1, Math.round(imgH * ratio));

                        Bitmap scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
                        String outBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                        return "data:image/png;base64," + outBase64;
                    } catch (Throwable t) {
                        return dataUrl;
                    }
                }
            }, "AndroidSkinBridge");
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                dispatchBackToWebView();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void applySystemImmersiveMode(boolean immersive) {
        try {
            Window window = getWindow();
            View decorView = window.getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(!immersive);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    if (immersive) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                if (immersive) {
                    decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                    );
                } else {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void sendOcrCallback(String reqId, String text, String err) {
        runOnUiThread(() -> {
            if (this.bridge != null && this.bridge.getWebView() != null) {
                String safeReqId = JSONObject.quote(reqId != null ? reqId : "");
                String safeText = JSONObject.quote(text != null ? text : "");
                String safeErr = JSONObject.quote(err != null ? err : "");
                this.bridge.getWebView().evaluateJavascript(
                    "if(window.__onNativeOcrResult){window.__onNativeOcrResult(" + safeReqId + "," + safeText + "," + safeErr + ");}",
                    null
                );
            }
        });
    }

    private void dispatchBackToWebView() {
        if (this.bridge != null && this.bridge.getWebView() != null) {
            WebView webView = this.bridge.getWebView();
            webView.evaluateJavascript(
                "(function() { " +
                "  if (typeof window.__handleAndroidNativeBack === 'function') { " +
                "    return window.__handleAndroidNativeBack(); " +
                "  } " +
                "  document.dispatchEvent(new CustomEvent('backbutton')); " +
                "  return false; " +
                "})()",
                result -> {}
            );
        } else {
            this.moveTaskToBack(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dispatchBackToWebView();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        dispatchBackToWebView();
    }
}
