package com.jarvis.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new JarvisBridge(), "AndroidJarvis");
        web.setBackgroundColor(0xFF02070C);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private class JarvisBridge {
        @JavascriptInterface public boolean accessibilityEnabled() { return JarvisAccessibilityService.isConnected(); }
        @JavascriptInterface public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }
        @JavascriptInterface public String execute(String action, String value) {
            return JarvisAccessibilityService.runCommand(action, value);
        }
        @JavascriptInterface public String openApp(String requestedName) {
            final String wanted = requestedName == null ? "" : requestedName.toLowerCase(Locale.ROOT).trim();
            if (wanted.isEmpty()) return "EMPTY_APP_NAME";
            PackageManager pm = getPackageManager();
            for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                String label = pm.getApplicationLabel(app).toString();
                if (label.toLowerCase(Locale.ROOT).equals(wanted) || label.toLowerCase(Locale.ROOT).contains(wanted)) {
                    Intent launch = pm.getLaunchIntentForPackage(app.packageName);
                    if (launch != null) {
                        runOnUiThread(() -> { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); });
                        return "OPENING:" + label;
                    }
                }
            }
            return "APP_NOT_FOUND";
        }
    }
}
