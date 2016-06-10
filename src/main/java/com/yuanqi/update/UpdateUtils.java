package com.yuanqi.update;

import android.content.Context;

import com.yuanqi.base.utils.DeviceConfig;
import com.yuanqi.update.model.UpdateResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Stack;

import timber.log.Timber;

/**
 * Tools for update sdk.
 * Created by nengxiangzhou on 16/4/12.
 */
class UpdateUtils {
  private static final Object lock = new Object();
  private static Thread clearThread;

  /**
   * format response to readable content
   * 
   * @return update dialog content.
   */
  public static String toUpdateContentString(Context context, UpdateResponse response,
      boolean isDownloaded) {
    String versionPrefix = context.getString(R.string.update_new_version);
    String updateLogPrefix = context.getString(R.string.update_content);
    String installApk = context.getString(R.string.update_install_apk);

    if (isDownloaded) {
      return String.format("%s %s\n" +
          "%s\n\n" +
          "%s\n" +
          "%s\n",
          versionPrefix, response.version_name,
          installApk,
          updateLogPrefix,
          response.update_log);
    }

    return String.format("%s %s\n" +
        "%s\n" +
        "%s\n",
        versionPrefix, response.version_name,
        updateLogPrefix,
        response.update_log);
  }

  public static File getDownloadDir(Context context, boolean[] isSdCard) {
    File file;
    String mRoot;
    if (DeviceConfig.isSdCardWritable() && context.getExternalCacheDir() != null) {
      mRoot = context.getExternalCacheDir().getAbsolutePath();
      mRoot += Constants.CACHE_PATH;
      file = new File(mRoot);
      file.mkdirs();
      if (file.exists()) {
        isSdCard[0] = true;
        return file;
      }
    }
    mRoot = context.getCacheDir().getAbsolutePath();
    new File(mRoot).mkdir();
    setPermissions(mRoot, FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
    mRoot += Constants.CACHE_PATH;
    new File(mRoot).mkdir();
    setPermissions(mRoot, FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
    file = new File(mRoot);
    isSdCard[0] = false;
    return file;
  }

  /**
   * Use android native method to set permissions.
   * 
   * @param file the file path
   * @param mode permission mode
   * @param uid -1
   * @param gid -1
   * @return
   */
  private static boolean setPermissions(String file, int mode, int uid, int gid) {
    try {
      Class<?> class1 = Class.forName("android.os.FileUtils");
      Method method =
          class1.getMethod("setPermissions", String.class, int.class, int.class, int.class);
      method.invoke(null, file, mode, uid, gid);
      return true;
    } catch (Exception e) {
      Timber.e("error when set permissions:", e);
    }
    return false;
  }

  @SuppressWarnings("deprecation")
  public static boolean setFilePermissionsFromMode(String name, int mode) {
    int perms = FileUtils.S_IRUSR | FileUtils.S_IWUSR
        | FileUtils.S_IRGRP | FileUtils.S_IWGRP;
    if ((mode & Context.MODE_WORLD_READABLE) != 0) {
      perms |= FileUtils.S_IROTH;
    }
    if ((mode & Context.MODE_WORLD_WRITEABLE) != 0) {
      perms |= FileUtils.S_IWOTH;
    }
    return setPermissions(name, perms, -1, -1);
  }

  /**
   * Check the path, if the size is larger than cache_size , clear the files which are overdue.
   * 
   * @param dir
   * @param cacheSize
   * @param overdueTime
   * @throws IOException
   */
  public static void checkDir(File dir, long cacheSize, final long overdueTime)
      throws IOException {
    if (dir.exists()) {
      if (dirSize(dir.getCanonicalFile()) > cacheSize) {
        final File cacheDir = dir;
        if (clearThread == null) {
          clearThread = new Thread(new Runnable() {
            @Override
            public void run() {
              cleanDir(cacheDir, overdueTime); // clean up old files.
              clearThread = null;
            }
          });
        }
        synchronized (lock) {
          clearThread.start();
        }
      }
    } else {
      if (!dir.mkdirs()) {
        Timber.e("Failed to create directory" + dir.getAbsolutePath()
            + ". Check permission. Make sure WRITE_EXTERNAL_STORAGE is added in your Manifest.xml");
      }
    }
  }

  private static long dirSize(File dir) {
    if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
    long result = 0;

    Stack<File> dirList = new Stack<>();
    dirList.clear();

    dirList.push(dir);

    while (!dirList.isEmpty()) {
      File dirCurrent = dirList.pop();

      File[] fileList = dirCurrent.listFiles();
      for (File aFileList : fileList) {

        if (!aFileList.isDirectory())
          result += aFileList.length();
      }
    }

    return result;
  }

  /**
   * Clean up old file in dir if file is > 30 min. Recursively.
   *
   * @param dir
   * @return
   */
  private static void cleanDir(File dir, long overdueTime) {
    if (dir == null || !dir.exists() || !dir.canWrite() || !dir.isDirectory()) return;
    File[] fileList = dir.listFiles();
    for (File aFileList : fileList) {
      if (!aFileList.isDirectory()
          && new Date().getTime() - aFileList.lastModified() > overdueTime) {
        aFileList.delete();
      }
    }
  }

  /**
   * 获取文件MD5
   *
   * @param file
   * @return 文件的SHA-1
   */
  public static String getFileSHA1(File file) {
    MessageDigest digest;
    FileInputStream in;
    byte buffer[] = new byte[1024];
    int len;
    try {
      if (!file.isFile()) {
        return "";
      }
      digest = MessageDigest.getInstance("SHA-1");
      in = new FileInputStream(file);
      while ((len = in.read(buffer, 0, 1024)) != -1) {
        digest.update(buffer, 0, len);
      }
      in.close();
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
    BigInteger bigInt = new BigInteger(1, digest.digest());
    return String.format("%1$032x", bigInt);
  }

  /**
   * SHA1 加密
   *
   * @param content
   * @return 32位16进制 密文 或 ""
   */
  public static String getSHA1(String content) {
    if (content == null)
      return null;
    try {
      byte[] defaultBytes = content.getBytes();
      MessageDigest algorithm = MessageDigest.getInstance("SHA-1");
      algorithm.reset();
      algorithm.update(defaultBytes);
      byte messageDigest[] = algorithm.digest();
      StringBuilder hexString = new StringBuilder();
      for (byte aMessageDigest : messageDigest) {
        hexString.append(String.format("%02X", aMessageDigest));
      }
      return hexString.toString();
    } catch (Exception e) {
      return content.replaceAll("[^[a-z][A-Z][0-9][.][_]]", "");
    }
  }
}
