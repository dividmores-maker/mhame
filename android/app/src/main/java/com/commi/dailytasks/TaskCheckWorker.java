package com.commi.dailytasks;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * بيشتغل في الخلفية كل ~15 دقيقة طالما فيه نت (حتى والتطبيق مقفول)،
 * بيقرا المهام من Firestore وبيبعت إشعار بالمهام اللي ماخلصتش.
 */
public class TaskCheckWorker extends Worker {

    private static final String API_KEY = "AIzaSyARriEtI1cRvxR7rIDXrnoKMURFR5YP4lA";
    private static final String PROJECT = "lumara-edd54";
    private static final String CHANNEL_ID = "pending_tasks";
    private static final String WORK_NAME = "pending-tasks-check";

    private static final long RECONNECT_GAP = 45L * 60 * 1000;   // لو عدّى أكتر من كده من آخر فحص = كان مفيش نت
    private static final long MIN_BETWEEN = 30L * 60 * 1000;     // أقل فاصل بين إشعارين
    private static final long REPEAT_EVERY = 6L * 60 * 60 * 1000; // تذكير دوري لو النت فاضل شغال

    public TaskCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context ctx) {
        Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(TaskCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(c)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("task_check", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastRun = sp.getLong("lastRun", 0);
        long lastNotify = sp.getLong("lastNotify", 0);

        List<String> pending;
        try {
            pending = fetchPending();
        } catch (Exception e) {
            return Result.retry();
        }
        sp.edit().putLong("lastRun", now).apply();
        if (pending.isEmpty()) return Result.success();

        boolean reconnected = lastRun == 0 || (now - lastRun) > RECONNECT_GAP;
        boolean spacedOut = (now - lastNotify) > MIN_BETWEEN;
        boolean repeatDue = (now - lastNotify) > REPEAT_EVERY;
        if ((reconnected && spacedOut) || repeatDue) {
            if (notifyPending(ctx, pending)) {
                sp.edit().putLong("lastNotify", now).apply();
            }
        }
        return Result.success();
    }

    private List<String> fetchPending() throws Exception {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        List<String> out = new ArrayList<>();
        String pageToken = null;
        do {
            String u = "https://firestore.googleapis.com/v1/projects/" + PROJECT
                    + "/databases/(default)/documents/daily_tasks?pageSize=300&key=" + API_KEY
                    + (pageToken != null ? "&pageToken=" + pageToken : "");
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                throw new Exception("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray docs = root.optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject doc = docs.getJSONObject(i);
                    String name = doc.optString("name", "");
                    String id = name.substring(name.lastIndexOf('/') + 1);
                    if (id.compareTo(today) > 0) continue;
                    JSONObject fields = doc.optJSONObject("fields");
                    if (fields == null) continue;
                    JSONObject tasksF = fields.optJSONObject("tasks");
                    if (tasksF == null) continue;
                    JSONObject arr = tasksF.optJSONObject("arrayValue");
                    if (arr == null) continue;
                    JSONArray vals = arr.optJSONArray("values");
                    if (vals == null) continue;
                    for (int j = 0; j < vals.length(); j++) {
                        JSONObject m = vals.getJSONObject(j).optJSONObject("mapValue");
                        if (m == null) continue;
                        JSONObject f = m.optJSONObject("fields");
                        if (f == null) continue;
                        JSONObject doneF = f.optJSONObject("done");
                        boolean done = doneF != null && doneF.optBoolean("booleanValue", false);
                        if (done) continue;
                        JSONObject textF = f.optJSONObject("text");
                        String text = textF != null ? textF.optString("stringValue", "") : "";
                        if (text.length() == 0) continue;
                        JSONObject fromF = f.optJSONObject("from");
                        String from = fromF != null ? fromF.optString("stringValue", id) : id;
                        String label = text;
                        try {
                            Date d1 = df.parse(from);
                            Date d2 = df.parse(today);
                            long days = Math.round((d2.getTime() - d1.getTime()) / 86400000.0);
                            if (days >= 1) label = text + " — من " + (days == 1 ? "يوم" : days == 2 ? "يومين" : days + " أيام");
                        } catch (Exception ignored) { }
                        out.add(label);
                    }
                }
            }
            pageToken = root.optString("nextPageToken", "");
            if (pageToken.length() == 0) pageToken = null;
        } while (pageToken != null);
        return out;
    }

    private boolean notifyPending(Context ctx, List<String> pending) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "المهام المعلّقة", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("تنبيه بالمهام اللي ماخلصتش");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        int n = pending.size();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < n && i < 5; i++) {
            if (i > 0) body.append("\n");
            body.append("• ").append(pending.get(i));
        }
        if (n > 5) body.append("\nو").append(n - 5).append(" غيرها");

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("⚠️ " + n + " مهمة ماخلصتش")
                .setContentText(pending.get(0))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(4101, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
