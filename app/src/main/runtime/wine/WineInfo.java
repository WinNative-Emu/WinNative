package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import androidx.annotation.NonNull;
import com.winlator.cmod.R;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
  public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton", "9.0", "x86_64");
  private static final Pattern pattern =
      Pattern.compile("^(wine|proton)\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
  public final String version;
  public final String type;
  public String subversion;
  public final String path;
  private String arch;

  public WineInfo(String type, String version, String arch) {
    this.type = type;
    this.version = version;
    this.subversion = null;
    this.arch = arch;
    this.path = null;
  }

  public WineInfo(String type, String version, String subversion, String arch, String path) {
    this.type = type;
    this.version = version;
    this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
    this.arch = arch;
    this.path = path;
  }

  public WineInfo(String type, String version, String arch, String path) {
    this.type = type;
    this.version = version;
    this.arch = arch;
    this.path = path;
  }

  private WineInfo(Parcel in) {
    type = in.readString();
    version = in.readString();
    subversion = in.readString();
    arch = in.readString();
    path = in.readString();
  }

  public String getArch() {
    return arch;
  }

  public void setArch(String arch) {
    this.arch = arch;
  }

  public boolean isWin64() {
    return arch.equals("x86_64") || arch.equals("arm64ec");
  }

  public boolean isArm64EC() {
    return arch.equals("arm64ec");
  }

  public String identifier() {
    if (type.equals("proton")) return "proton-" + fullVersion() + "-" + arch;
    else return "wine-" + fullVersion() + "-" + arch;
  }

  public String fullVersion() {
    return version + (subversion != null ? "-" + subversion : "");
  }

  @NonNull @Override
  public String toString() {
    if (type.equals("proton"))
      return "Proton " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
    else return "Wine " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Parcelable.Creator<WineInfo> CREATOR =
      new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
          return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
          return new WineInfo[size];
        }
      };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(type);
    dest.writeString(version);
    dest.writeString(subversion);
    dest.writeString(arch);
    dest.writeString(path);
  }

  @NonNull public static WineInfo fromIdentifier(
      Context context, ContentsManager contentsManager, String identifier) {
    ImageFs imageFs = ImageFs.find(context);
    String path = "";

    Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);

    if (identifier.equals(MAIN_WINE_VERSION.identifier()))
      return new WineInfo(
          MAIN_WINE_VERSION.type,
          MAIN_WINE_VERSION.version,
          MAIN_WINE_VERSION.arch,
          imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());

    ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier);
    Log.d(
        "WineInfo",
        "Profile lookup for '"
            + identifier
            + "' => "
            + (wineProfile != null
                ? "found (type=" + wineProfile.type + ", ver=" + wineProfile.verName + ")"
                : "null"));

    // Try regex on original identifier first (preserves arch like arm64ec), then fallback to
    // lowercase/stripped
    String originalIdentifier = identifier;
    Matcher matcher = pattern.matcher(originalIdentifier.toLowerCase(Locale.ROOT));
    boolean matched = matcher.find();

    if (!matched) {
      // Also try stripping the version code suffix for custom protons: "Proton-9.0-1" ->
      // "proton-9.0"
      // and appending a default arch
      if (wineProfile != null) {
        String normalized;
        if (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
          normalized = "proton-" + wineProfile.verName + "-x86_64";
        } else if (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE) {
          normalized = "wine-" + wineProfile.verName + "-x86_64";
        } else {
          normalized = originalIdentifier.toLowerCase(Locale.ROOT);
        }
        Log.d("WineInfo", "Trying normalized identifier: " + normalized);
        matcher = pattern.matcher(normalized);
        matched = matcher.find();
      }
    }

    if (matched) {
      String matchedIdentifier = matcher.group(0); // The full match for path resolution
      String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
      for (String wineVersion : wineVersions) {
        if (wineVersion.contains(matchedIdentifier)) {
          path = imageFs.getRootDir().getPath() + "/opt/" + matchedIdentifier;
          break;
        }
      }

      if (wineProfile != null
          && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
              || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON))
        path = ContentsManager.getInstallDir(context, wineProfile).getPath();

      String arch = matcher.group(4);
      Log.d(
          "WineInfo",
          "Resolved arch=" + arch + " path=" + path + " from identifier " + originalIdentifier);
      return new WineInfo(matcher.group(1), matcher.group(2), arch, path);
    } else if (wineProfile != null
        && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
            || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
      // Custom proton/wine profile that doesn't match the standard regex format
      // Construct WineInfo directly from the profile metadata
      String type =
          wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON ? "proton" : "wine";
      String version = wineProfile.verName;
      String arch = "x86_64"; // Default for custom protons

      if ((wineProfile.wineLibPath != null
              && wineProfile.wineLibPath.toLowerCase(Locale.ROOT).contains("arm64ec"))
          || (wineProfile.verName != null
              && wineProfile.verName.toLowerCase(Locale.ROOT).contains("arm64ec"))) {
        arch = "arm64ec";
      }

      path = ContentsManager.getInstallDir(context, wineProfile).getPath();
      Log.d(
          "WineInfo",
          "Constructed WineInfo from profile: type="
              + type
              + " version="
              + version
              + " arch="
              + arch
              + " path="
              + path);
      return new WineInfo(type, version, arch, path);
    } else {
      Log.w(
          "WineInfo",
          "Failed to parse identifier '"
              + originalIdentifier
              + "', falling back to MAIN_WINE_VERSION (x86_64)");
      return new WineInfo(
          MAIN_WINE_VERSION.type,
          MAIN_WINE_VERSION.version,
          MAIN_WINE_VERSION.arch,
          imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
    }
  }

  public static boolean isMainWineVersion(String wineVersion) {
    return wineVersion == null || wineVersion.equals(MAIN_WINE_VERSION.identifier());
  }
}
