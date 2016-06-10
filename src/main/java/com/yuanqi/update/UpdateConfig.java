package com.yuanqi.update;

import android.content.Context;
import android.text.TextUtils;

/**
 * A class to hold global update configure.
 * <p/>
 * Created by nengxiangzhou on 16/4/13.
 *
 */
public class UpdateConfig {
  private static final String PREFS_UMENG_UPDATE = "update";
  private static final String KEY_IGNORE_MD5S = "ignore";

  private static boolean mUpdateForce = false;

  public static void saveIgnoreMd5(Context context, String md5) {
    context.getApplicationContext()
        .getSharedPreferences(PREFS_UMENG_UPDATE, Context.MODE_PRIVATE)
        .edit().putString(KEY_IGNORE_MD5S, md5).commit();
  }

  public static String getIgnoreMd5(Context context) {
    String md5 = context.getApplicationContext()
        .getSharedPreferences(PREFS_UMENG_UPDATE, Context.MODE_PRIVATE)
        .getString(KEY_IGNORE_MD5S, null);
    return TextUtils.isEmpty(md5) ? null : md5;
  }

  public static boolean isUpdateForce() {
    return mUpdateForce;
  }

  public static void setUpdateForce(boolean mUpdateForce) {
    UpdateConfig.mUpdateForce = mUpdateForce;
  }
}
