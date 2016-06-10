package com.yuanqi.update;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Messenger;
import android.support.v4.app.NotificationCompat.Builder;
import android.util.SparseArray;

import com.yuanqi.update.DownloadAgent.DownloadItem;
import com.yuanqi.update.DownloadingService.DownloadingThread;

import java.util.List;
import java.util.Map;

import timber.log.Timber;

class DownloadTool {
  /**
   * Check if the download request is already processed and is in downloading.
   * 
   * @param item
   * @param replyTo
   * @return
   */
  static boolean isInDownloadList(DownloadItem item, Map<DownloadItem, Messenger> clients,
      Messenger replyTo) {
    if (clients == null)
      return false;
    for (DownloadItem d : clients.keySet()) {
      if (item.sign != null && item.sign.equals(d.sign)) {
        clients.put(d, replyTo);
        return true;
      }
    }
    return false;
  }

  static int buildNotificationID(DownloadItem item) {
    int id = (int) ((item.title.hashCode() >> 2)
        + (item.url.hashCode() >> 3) + System.currentTimeMillis());
    return Math.abs(id);
  }

  static Builder getNotification(Context context, DownloadItem item, int prePos) {
    context = context.getApplicationContext();
    Builder builder = new Builder(context);
    PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
        new Intent(), PendingIntent.FLAG_UPDATE_CURRENT);
    builder.setTicker(context.getString(R.string.update_start_download_notification))
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentIntent(contentIntent)
        .setWhen(System.currentTimeMillis())
        .setContentTitle(
            context.getString(R.string.update_download_notification_prefix) + item.title)
        .setProgress(100, prePos, false);
    return builder;
  }


  /**
   * determine whether the current app is in the foreground.
   * 
   * @param context
   * @return
   */
  static boolean isAppOnForeground(Context context) {
    ActivityManager activityManager = (ActivityManager) context
        .getSystemService(Context.ACTIVITY_SERVICE);
    List<RunningAppProcessInfo> appProcesses = activityManager.getRunningAppProcesses();
    if (appProcesses == null) {
      return false;
    }
    final String packageName = context.getPackageName();
    for (RunningAppProcessInfo appProcess : appProcesses) {
      if (appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
          && appProcess.processName.equals(packageName)) {
        return true;
      }
    }
    return false;
  }

  static void clearCache(Context context, SparseArray<Pair> pairCache,
      Map<DownloadItem, Messenger> clients, int nid) {
    NotificationManager notificationManager = (NotificationManager) context
        .getSystemService(Context.NOTIFICATION_SERVICE);
    Pair pair = pairCache.get(nid);
    if (pair != null) {
      Timber.d("download service clear cache " + pair.item.title);
      if (pair.thread != null)
        pair.thread.safeDestroy(2);
      notificationManager.cancel(pair.nid);
      if (clients.containsKey(pair.item))
        clients.remove(pair.item);
      pair.dumpCache(pairCache);
      // debug();
    }
  }


  static class Pair {
    DownloadingThread thread;
    Builder currentNotification;
    final int nid;
    final DownloadItem item;
    final long[] backup = new long[3];

    public Pair(DownloadItem item, int nid) {
      super();
      this.nid = nid;
      this.item = item;
    }

    public void pushToCache(SparseArray<Pair> pairCache) {
      pairCache.put(nid, this);
    }

    public void dumpCache(SparseArray<Pair> pairCache) {
      if (pairCache.indexOfKey(nid) >= 0)
        pairCache.remove(nid);
    }
  }
}
