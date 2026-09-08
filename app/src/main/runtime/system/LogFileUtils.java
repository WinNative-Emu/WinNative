package com.winlator.cmod.runtime.system;

import android.content.Context;
import android.text.format.DateFormat;
import java.io.File;
import java.util.Date;
import java.util.Locale;

public final class LogFileUtils {
  private static String fileName;

  private LogFileUtils() {}

  public static void setFilename(String file) {
    fileName = file.substring(0, file.lastIndexOf("."));
  }

  public static File getLogFile(Context context) {
    File logsDir = LogManager.getLogsDir(context);
    String logFile =
        fileName.replaceAll("[^a-zA-Z0-9\\-_]", "_").toLowerCase(Locale.ROOT)
            + "_"
            + DateFormat.format("yyyy-MM-dd_HH-mm-ss", new Date())
            + ".txt";
    return new File(logsDir, logFile);
  }
}
