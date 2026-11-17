package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String MODEL = "gemini-flash-lite-latest";
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String BASE_SYSTEM =
        "Sei J.A.R.V.I.S., assistente personale in italiano. "
        + "Rispondi in massimo due frasi brevi, adatte alla voce, senza elenchi ne' simboli. "
        + "Ogni tanto chiama l'utente 'Signore'. "
        + "Puoi solo conversare: se ti chiedono di eseguire azioni sul tablet, "
        + "rispondi che per quello servono comandi come 'apri YouTube'.";

    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final List<JSONObject> history = new ArrayList<>();

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

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ITALIAN);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { js("window.onNativeSpeakDone && window.onNativeSpeakDone()"); }
                    @Override public void onError(String id) { js("window.onNativeSpeakDone && window.onNativeSpeakDone()"); }
                });
                ttsReady = true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        if (prefs().getString("key", "").isEmpty()) showKeyDialog();
    }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    private SharedPreferences prefs() { return getSharedPreferences("jarvis", MODE_PRIVATE); }

    private void js(final String script) {
        runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private void showKeyDialog() {
        runOnUiThread(() -> {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setHint("Chiave API Gemini");
            new AlertDialog.Builder(this)
                .setTitle("Chiave Gemini")
                .setMessage("Incolla la chiave. Resta salvata solo su questo tablet.")
                .setView(input)
                .setPositiveButton("Salva", (d, w) -> {
                    String k = input.getText().toString().trim();
                    if (!k.isEmpty()) prefs().edit().putString("key", k).apply();
                })
                .setNegativeButton("Annulla", null)
                .show();
        });
    }

    private String systemPrompt() {
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(new Date());
        return BASE_SYSTEM + " Data e ora attuali: " + now + ".";
    }

    private JSONObject content(String role, String text) throws Exception {
        return new JSONObject().put("role", role)
            .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    private String askGemini(String text) {
        String key = prefs().getString("key", "");
        if (key.isEmpty()) return "Manca la chiave di Gemini, Signore. Di': cambia chiave.";
        JSONObject userMsg = null;
        try {
            userMsg = content("user", text);
            JSONArray contents = new JSONArray();
            synchronized (history) {
                history.add(userMsg);
                while (history.size() > 20) history.remove(0);
                for (JSONObject h : history) contents.put(h);
            }
            JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", systemPrompt()))))
                .put("contents", contents);

            HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + MODEL + ":generateContent").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("x-goog-api-key", key);
            c.setRequestProperty("content-type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code != 200) {
                synchronized (history) { history.remove(userMsg); }
                if (code == 400 || code == 401 || code == 403)
                    return "La chiave non sembra valida, Signore. Di': cambia chiave.";
                return "Errore " + code + " dal servizio, Signore.";
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream is = c.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray parts = new JSONObject(sb.toString())
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
            StringBuilder reply = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) reply.append(parts.getJSONObject(i).optString("text", ""));
            String r = reply.toString().trim();
            if (r.isEmpty()) {
                synchronized (history) { history.remove(userMsg); }
                return "Non ho una risposta, Signore.";
            }
            synchronized (history) { history.add(content("model", r)); }
            return r;
        } catch (Exception e) {
            if (userMsg != null) synchronized (history) { history.remove(userMsg); }
            return "Errore di connessione, Signore.";
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { js("window.onNativeListening && window.onNativeListening()"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle partialResults) { }
        @Override public void onEvent(int eventType, Bundle params) { }
        @Override public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                js("window.onNativeSpeech && window.onNativeSpeech(" + JSONObject.quote(list.get(0)) + ")");
            }
            js("window.onNativeListenEnd && window.onNativeListenEnd()");
        }
        @Override public void onError(int error) {
            String name;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) name = "permission";
            else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) name = "silence";
            else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) name = "busy";
            else if (error == SpeechRecognizer.ERROR_CLIENT) name = "aborted";
            else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) name = "network";
            else name = "error" + error;
            js("window.onNativeSpeechError && window.onNativeSpeechError(" + JSONObject.quote(name) + ")");
            js("window.onNativeListenEnd && window.onNativeListenEnd()");
        }
    };

    private class JarvisBridge {
        @JavascriptInterface public boolean nativeVoice() { return true; }

        @JavascriptInterface public void startListening() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    js("window.onNativeSpeechError && window.onNativeSpeechError('permission')");
                    return;
                }
                if (recognizer == null) {
                    if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                        js("window.onNativeSpeechError && window.onNativeSpeechError('unavailable')");
                        return;
                    }
                    recognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                    recognizer.setRecognitionListener(listener);
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                recognizer.startListening(i);
            });
        }
        @JavascriptInterface public void stopListening() {
            runOnUiThread(() -> { if (recognizer != null) recognizer.cancel(); });
        }
        @JavascriptInterface public void speak(final String text) {
            runOnUiThread(() -> {
                if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
                else js("window.onNativeSpeakDone && window.onNativeSpeakDone()");
            });
        }
        @JavascriptInterface public void stopSpeaking() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }
        @JavascriptInterface public void ask(final String text) {
            new Thread(() -> {
                String reply = askGemini(text);
                js("window.onNativeReply && window.onNativeReply(" + JSONObject.quote(reply) + ")");
            }).start();
        }
        @JavascriptInterface public void changeKey() { showKeyDialog(); }
        @JavascriptInterface public int battery() {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }

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
