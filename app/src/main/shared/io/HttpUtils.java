package com.winlator.cmod.shared.io;

import android.app.Activity;
import com.winlator.cmod.shared.ui.dialog.DownloadProgressDialog;
import com.winlator.cmod.shared.util.Callback;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class HttpUtils {
  private static final int CONNECT_TIMEOUT_MS = 15000;
  private static final int READ_TIMEOUT_MS = 30000;

  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "HttpUtils");
            thread.setDaemon(true);
            return thread;
          });

  private static HttpURLConnection open(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) (new URL(url)).openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setInstanceFollowRedirects(true);
    return connection;
  }

  private static void downloadAsync(String url, Callback<String> onDownloadComplete) {
    HttpURLConnection connection = null;
    try {
      connection = open(url);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        onDownloadComplete.call(null);
        return;
      }

      byte[] bytes;
      try (InputStream inStream = connection.getInputStream()) {
        bytes = StreamUtils.copyToByteArray(inStream);
      }
      onDownloadComplete.call(new String(bytes, StandardCharsets.UTF_8));
    } catch (Exception e) {
      onDownloadComplete.call(null);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public static void download(final String url, final Callback<String> onDownloadComplete) {
    EXECUTOR.execute(() -> downloadAsync(url, onDownloadComplete));
  }

  private static void downloadAsync(
      String url,
      File destination,
      AtomicBoolean interruptRef,
      Callback<Integer> onPublishProgress,
      Callback<Boolean> onDownloadComplete) {
    HttpURLConnection connection = null;
    try {
      interruptRef.set(false);
      connection = open(url);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        onDownloadComplete.call(false);
        return;
      }

      long contentLength = connection.getContentLengthLong();
      try (InputStream inStream =
              new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
          OutputStream outStream = new FileOutputStream(destination)) {

        byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
        long totalSize = 0;
        int lastProgress = -1;
        int bytesRead;
        while ((bytesRead = inStream.read(buffer)) != -1 && !interruptRef.get()) {
          totalSize += bytesRead;
          if (onPublishProgress != null && contentLength > 0) {
            int progress = (int) Math.min(100L, totalSize * 100L / contentLength);
            if (progress != lastProgress) {
              lastProgress = progress;
              onPublishProgress.call(progress);
            }
          }
          outStream.write(buffer, 0, bytesRead);
        }
      }

      onDownloadComplete.call(!interruptRef.get());
    } catch (Exception e) {
      onDownloadComplete.call(false);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public static void download(
      final Activity activity,
      final String url,
      final File destination,
      final Callback<Boolean> onDownloadComplete) {
    final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
    final AtomicBoolean interruptRef = new AtomicBoolean();
    dialog.show(() -> interruptRef.set(true));
    EXECUTOR.execute(
        () ->
            downloadAsync(
                url,
                destination,
                interruptRef,
                (progress) -> activity.runOnUiThread(() -> dialog.setProgress(progress)),
                (success) -> {
                  if (!success && destination.isFile()) destination.delete();
                  activity.runOnUiThread(
                      () -> {
                        dialog.close();
                        onDownloadComplete.call(success);
                      });
                }));
  }
}
