package me.littlekey.update;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.util.SparseArray;
import android.widget.Toast;

import me.littlekey.base.utils.DeviceConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

/**
 *
 */
public class DownloadingService extends Service {
  public static final int MSG_START = 1;
  public static final int MSG_STATUS = 2;
  public static final int MSG_COMPLETE = 3;
  /**
   * Command to start download from client.
   */
  public static final int MSG_DOWNLOAD = 4;

  private static final int DOWNLOAD_COMPLETE_FAIL = 0;
  private static final int DOWNLOAD_COMPLETE_SUCCESS = 1;
  public static final int DOWNLOAD_IS_DOWNLOADING = 2;
  private static final int DOWNLOAD_NO_NETWORK = 4;
  /**
   * Bundle key for filename, used for service to inform client the downloaded
   * file path.
   */
  private static final String BUNDLE_KEY_FILE_NAME = "filename";
  private static final int DOWNLOAD_PROGRESS_COMPLETE = 100;
  /**
   * SD card cache size to store the cached resources. Default to 100MB;
   */
  private static final long EXTERNAL_CACHE_SIZE = 100 * 1024 * 1024;
  /**
   * Internal cache size to store the cached resources. Default to 30MB.
   */
  private static final long INTERNAL_CACHE_SIZE = 30 * 1024 * 1024;
  /**
   * Cache time to store the cache apk files. Default to 3 days.
   */
  private static final long OVERDUE_TIME = 3L * 24L * 60L * 60L * 1000L;
  private static final int MAX_REPEAT_COUNT = 3;
  private static final long WAIT_FOR_REPEAT = 8 * 1000;
  /**
   * The clients connected to the service. Messenger references the client,
   * where DownloadItem specifies the information required by this service.
   */
  private static final Map<DownloadAgent.DownloadItem, Messenger> sClients = new HashMap<>();
  /**
   * notification id --> {thread,notification,item}
   */
  private static final SparseArray<DownloadTool.Pair> sPairCache = new SparseArray<>();
  private static NotificationManager sNotificationManager;
  /**
   * To handle incoming request for downloads from client.
   */
  private final Messenger mMessenger = new Messenger(new IncomingHandler(this));
  private DownloadThreadListener mDownloadThreadListener;
  private Context mContext;
  private final Handler mHandler = new Handler();

  /**
   * When binding to the service, we return an interface to our messenger for
   * sending messages to the service.
   */
  @Override
  public IBinder onBind(Intent intent) {
    Timber.d("onBind ");
    return mMessenger.getBinder();
  }

  @SuppressWarnings("deprecation")
  @Override
  public void onStart(Intent intent, int startId) {
    Timber.d("onStart ");
    super.onStart(intent, startId);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Timber.d("onCreate ");
    sNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    mContext = this;
    mDownloadThreadListener = new DownloadThreadListener() {

      @Override
      public void onStart(int nid) {
        // sNotificationManager.cancel(nid);
        if (sPairCache.indexOfKey(nid) >= 0) {
          DownloadTool.Pair pair = sPairCache.get(nid);
          long[] backup = pair.backup;
          int prePos = 0;
          if (backup[1] > 0) {
            float per = (float) backup[0] / (float) backup[1];
            prePos = (int) (per * 100);

            if (prePos > 100)
              prePos = 99;
          }

          NotificationCompat.Builder mBuilder = DownloadTool.getNotification(
              DownloadingService.this, pair.item, prePos);
          pair.currentNotification = mBuilder;
          sNotificationManager.notify(nid, mBuilder.build());
        }
      }

      @Override
      public void onProgress(int nid, int progress) {
        if (sPairCache.indexOfKey(nid) >= 0) {
          DownloadTool.Pair pair = sPairCache.get(nid);
          DownloadAgent.DownloadItem item = pair.item;
          NotificationCompat.Builder mBuilder = pair.currentNotification;
          mBuilder.setProgress(100, progress, false)
              .setContentText(String.valueOf(progress) + "%");
          sNotificationManager.notify(nid, mBuilder.build());
          Timber.d(String.format(
              "%3$10s Notification: mNotificationId = %1$15s	|	progress = %2$15s",
              nid, progress, item.title));
        }
      }

      @Override
      public void onEnd(final int nid, final String fileName) {
        if (sPairCache.indexOfKey(nid) >= 0) {
          DownloadTool.Pair pair = sPairCache.get(nid);
          if (pair != null) {
            final DownloadAgent.DownloadItem item = pair.item;
            NotificationCompat.Builder mBuilder = pair.currentNotification;
            mBuilder.setProgress(100, 100, false)
                .setContentText(String.valueOf(DOWNLOAD_PROGRESS_COMPLETE) + "%");
            sNotificationManager.notify(nid, mBuilder.build());
            Bundle data = new Bundle();
            data.putString(BUNDLE_KEY_FILE_NAME, fileName);

            mHandler.post(new Runnable() {
              @Override
              public void run() {
                handleDownloadComplete(item, fileName, nid);
              }
            });

            // send message to client.
            Message msg_to_client = Message.obtain();
            msg_to_client.what = MSG_COMPLETE;
            msg_to_client.arg1 = DOWNLOAD_COMPLETE_SUCCESS;
            msg_to_client.arg2 = nid;
            msg_to_client.setData(data);
            // send the message to client.
            try {
              if (sClients.get(item) != null) {
                sClients.get(item).send(msg_to_client);
              }
            } catch (RemoteException e) {
              e.printStackTrace();
            } finally {
              DownloadTool.clearCache(mContext, sPairCache, sClients, nid);
            }
          }
        }

      }

      @Override
      public void onError(int nid, Exception e) {
        if (sPairCache.indexOfKey(nid) >= 0) {
          DownloadTool.Pair pair = sPairCache.get(nid);
          DownloadAgent.DownloadItem item = pair.item;
          NotificationCompat.Builder mBuilder = pair.currentNotification;
          mBuilder.setProgress(0, 0, false)
              .setContentText(item.title + mContext.getString(R.string.update_download_failed));
          sNotificationManager.notify(nid, mBuilder.build());
          DownloadTool.clearCache(mContext, sPairCache, sClients, nid);
        }
      }
    };
  }

  private void handleDownloadComplete(DownloadAgent.DownloadItem item, String fileName, int notificationId) {
    try {
      Timber.d("Cancel old notification....");
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.setDataAndType(Uri.fromFile(new File(fileName)),
          "application/vnd.android.package-archive");
      PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent,
          PendingIntent.FLAG_UPDATE_CURRENT);
      NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext)
          .setSmallIcon(android.R.drawable.stat_sys_download_done)
          .setContentTitle(item.title)
          .setContentText(mContext.getString(R.string.update_download_finish))
          .setTicker(mContext.getString(R.string.update_download_finish))
          .setContentIntent(pendingIntent)
          .setWhen(System.currentTimeMillis());

      Notification notification = builder.build();
      notification.flags = Notification.FLAG_AUTO_CANCEL;

      sNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

      sNotificationManager.notify(notificationId + 1, notification);

      Timber.d("Show new  notification....");
      // the app is in the foreground, install
      // directly, otherwise, change the
      // notification so that when the user click
      // the notification, the installation
      // process starts.
      if (DownloadTool.isAppOnForeground(mContext)) {
        sNotificationManager.cancel(notificationId + 1);
        mContext.startActivity(intent);
      }

      Timber.i(String.format(
          "%1$10s downloaded. Saved to: %2$s",
          item.title, fileName));

    } catch (Exception e) {
      Timber.e("can not install. " + e.getMessage());
      // SUCCEED = false;
      sNotificationManager.cancel(notificationId + 1);
    }
  }

  private void startDownload(DownloadAgent.DownloadItem item) {
    Timber.d("startDownload([" + " title:" + item.title + " url:" + item.url + "])");

    int nId = DownloadTool.buildNotificationID(item);
    DownloadingThread thread =
        new DownloadingThread(this, item, nId, mDownloadThreadListener);

    DownloadTool.Pair pair = new DownloadTool.Pair(item, nId);
    pair.pushToCache(sPairCache);
    pair.thread = thread;

    thread.start();
  }

  interface DownloadThreadListener {
    void onStart(int nid);

    void onProgress(int nid, int progress);

    void onEnd(int nid, String fileName);

    void onError(int nid, Exception e);
  }

  /**
   * To handle incoming downloading request from client. Client packs download
   * information in Message.
   * <p/>
   * <ul>
   * <li>msg.what: {@link #MSG_DOWNLOAD} client requests service to download.</li>
   * <li>msg.arg1: no use.</li>
   * <li>msg.arg2: no use.</li>
   * <li>msg.replyTo Messenger, reference to client.</li>
   * <li>msg.getData(): {@link Bundle}, from
   * {@link DownloadAgent.DownloadItem#toBundle() DownloadItem}.</li>
   * </ul>
   *
   */
  static class IncomingHandler extends Handler {
    private final WeakReference<DownloadingService> service;

    public IncomingHandler(DownloadingService service) {
      super();
      this.service = new WeakReference<>(service);
    }

    @Override
    public void handleMessage(Message msg) {
      DownloadingService downloadingService = service.get();
      if (downloadingService == null) {
        return;
      }
      Context context = downloadingService.getApplicationContext();
      Timber.d("IncomingHandler(" + "msg.what:" + msg.what
          + " msg.arg1:" + msg.arg1 + " msg.arg2:" + msg.arg2
          + " msg.replyTo:" + msg.replyTo);
      switch (msg.what) {
        case MSG_DOWNLOAD:
          Bundle data = msg.getData();
          DownloadAgent.DownloadItem item = DownloadAgent.DownloadItem.fromBundle(data);
          // TODO check if the item is verified, such as item.md5 shouldn't be null.
          if (DownloadTool.isInDownloadList(item, sClients, msg.replyTo)) {
            // This task is already in downloading list.
            Toast.makeText(context, R.string.update_is_downloading, Toast.LENGTH_SHORT).show();
            Message msg_to_client = Message.obtain();
            msg_to_client.what = MSG_STATUS;
            msg_to_client.arg1 = DOWNLOAD_IS_DOWNLOADING;
            msg_to_client.arg2 = 0;
            try {
              msg.replyTo.send(msg_to_client);
            } catch (RemoteException e) {
              e.printStackTrace();
            }
            return;
          } else {
            // Create a new download task.
            if (DeviceConfig.isOnline(downloadingService.getApplicationContext())) {
              sClients.put(item, msg.replyTo);
              Message msg_to_client = Message.obtain();
              msg_to_client.what = MSG_START;
              msg_to_client.arg1 = MSG_START;
              msg_to_client.arg2 = 0;
              try {
                msg.replyTo.send(msg_to_client);
              } catch (RemoteException e) {
                e.printStackTrace();
              }
              downloadingService.startDownload(item);
            } else {
              // No network.
              Toast.makeText(context, R.string.update_bad_network_alert, Toast.LENGTH_SHORT).show();
              Message msg_to_client = Message.obtain();
              msg_to_client.what = MSG_STATUS;
              msg_to_client.arg1 = DOWNLOAD_NO_NETWORK;
              msg_to_client.arg2 = 0;
              try {
                msg.replyTo.send(msg_to_client);
              } catch (RemoteException e) {
                e.printStackTrace();
              }
            }
          }
          break;
        default:
          super.handleMessage(msg);
      }
    }
  }

  static class DownloadingThread extends Thread {
    private final WeakReference<DownloadingService> mService;
    private final Context mContext;
    private final boolean isSdCard;
    private File file;
    private int repeatCount = 0;
    private long accLen = -1;
    private long totalLength = -1;
    private int safeDestroy = -1;
    private final int mNotificationId;
    private final DownloadThreadListener mListener;
    private final DownloadAgent.DownloadItem mDownloadItem;

    // handle message if download complete and succeed

    public DownloadingThread(DownloadingService service, DownloadAgent.DownloadItem item, int nid,
                             DownloadThreadListener listener) {
      mService = new WeakReference<>(service);
      mContext = service.getApplicationContext();
      mDownloadItem = item;
      mNotificationId = nid;
      repeatCount = 0;
      mListener = listener;
      if (sPairCache.indexOfKey(nid) >= 0) {
        DownloadTool.Pair pair = sPairCache.get(nid);
        long[] backup = pair.backup;
        if (backup != null && backup.length > 1) {
          accLen = backup[0];
          totalLength = backup[1];
        }
      }
      boolean[] SdCard = new boolean[1];
      file = UpdateUtils.getDownloadDir(mContext, SdCard);
      isSdCard = SdCard[0];
      // TODO clean update download folder.
      // long cache_size = isSdCard
      // ? EXTERNAL_CACHE_SIZE
      // : INTERNAL_CACHE_SIZE;
      // UpdateUtils.checkDir(file, cache_size, OVERDUE_TIME);
      String fileName = getFileName(mDownloadItem);
      file = new File(file, fileName);
    }

    public void run() {
      repeatCount = 0;
      // add notification
      try {
        if (mListener != null)
          mListener.onStart(mNotificationId);
        saveAPK(accLen > 0);
        if (sClients.size() <= 0) {
          DownloadingService service = mService.get();
          if (service != null) {
            service.stopSelf();
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    /**
     * 1:backup percent for pause 2:don't backup for stop
     *
     * @param destroyFlag
     */
    public void safeDestroy(int destroyFlag) {
      safeDestroy = destroyFlag;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint({"WorldReadableFiles", "WorldWriteableFiles"})
    private void saveAPK(boolean isRepeat) {
      String fileName = file.getName();
      InputStream inputStream = null;
      FileOutputStream fileOutputStream = null;
      try {
        fileOutputStream = new FileOutputStream(file, true);
        if (!isSdCard) {
          if (!UpdateUtils.setFilePermissionsFromMode(
              file.getAbsolutePath(), Context.MODE_WORLD_READABLE
                  | Context.MODE_WORLD_WRITEABLE)) {
            fileOutputStream.close();
            fileOutputStream = mContext.openFileOutput(fileName,
                Context.MODE_WORLD_READABLE
                    | Context.MODE_WORLD_WRITEABLE
                    | Context.MODE_APPEND);
            file = mContext.getFileStreamPath(fileName);
          }
        }
        Timber.d(String.format(
            "saveAPK: url = %1$15s	|	filename = %2$15s",
            mDownloadItem.url, file.getAbsolutePath()));

        URL u = new URL(mDownloadItem.url);
        HttpURLConnection conn = initConnection(u, file);
        conn.connect();
        inputStream = conn.getInputStream();

        if (!isRepeat) {
          accLen = 0;
          totalLength = conn.getContentLength();
          Timber.d(String.format("getContentLength: %1$15s", totalLength));
        }

        byte[] buffer = new byte[4096];
        int cycle = 0, len;
        int STEP = 50;
        boolean SUCCEED = true;

        Timber.d(mDownloadItem.title + "saveAPK getContentLength " + String.valueOf(totalLength));

        while (safeDestroy < 0 && (len = inputStream.read(buffer)) > 0) {
          fileOutputStream.write(buffer, 0, len);
          accLen += len;
          if ((cycle++) % STEP == 0) {
            if (!DeviceConfig.isOnline(mContext)) {
              SUCCEED = false;
              break;
            }

            int progress = (int) ((float) accLen * 100 / (float) totalLength);
            if (progress > 100)
              progress = 99;

            if (mListener != null)
              mListener.onProgress(mNotificationId, progress);
          }
        }
        inputStream.close();
        fileOutputStream.close();

        if (safeDestroy == 1) {
          DownloadTool.Pair pair = sPairCache.get(mNotificationId);
          pair.backup[0] = accLen;
          pair.backup[1] = totalLength;
          pair.backup[2] = repeatCount;
          return;
        } else if (safeDestroy == 2) {
          // manual interrupt download thread without report.
          sNotificationManager.cancel(mNotificationId);
          return;
        }

        if (!SUCCEED) {
          Timber.e("Download Fail repeat count=" + repeatCount);
          sClients.get(mDownloadItem).send(
              Message.obtain(null, MSG_COMPLETE,
                  DOWNLOAD_COMPLETE_FAIL, 0));
          DownloadTool.clearCache(mContext, sPairCache, sClients,
              mNotificationId);
          if (mListener != null)
            mListener.onError(mNotificationId, null);
        } else {

          // sendXpReport();

          File newFile = new File(file.getParent(), file.getName()
              .replace(".tmp", ""));
          file.renameTo(newFile);
          fileName = newFile.getAbsolutePath();
          checkMd5(newFile);

          if (mListener != null)
            mListener.onEnd(mNotificationId, fileName);
        }
      } catch (IOException e) {
        Timber.d(e.getMessage(), e);
        if (++repeatCount > MAX_REPEAT_COUNT) {
          try {
            Timber.e("Download Fail out of max repeat count");
            sClients.get(mDownloadItem).send(
                Message.obtain(null, MSG_COMPLETE,
                    DOWNLOAD_COMPLETE_FAIL, 0));
          } catch (RemoteException e1) {
            e1.printStackTrace();
          } finally {
            DownloadTool.clearCache(mContext, sPairCache, sClients, mNotificationId);
            errDownload(e);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
              @Override
              public void run() {
                Toast.makeText(mContext, R.string.update_download_failed, Toast.LENGTH_SHORT)
                    .show();
              }
            });
          }

        } else {
          repeatConnect();
        }
      } catch (RemoteException e) {
        DownloadTool.clearCache(mContext, sPairCache, sClients, mNotificationId);
        e.printStackTrace();
      } finally {
        try {
          if (inputStream != null)
            inputStream.close();
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          try {
            if (fileOutputStream != null)
              fileOutputStream.close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
    }

    // repeat connect network
    private void repeatConnect() {
      Timber.d("wait for repeating Test network repeat count=" + repeatCount);
      try {
        Thread.sleep(WAIT_FOR_REPEAT);
        if (totalLength < 1)
          saveAPK(false);
        else
          saveAPK(true);
      } catch (InterruptedException e1) {
        errDownload(e1);
        DownloadTool.clearCache(mContext, sPairCache, sClients, mNotificationId);
      }
    }

    private HttpURLConnection initConnection(URL url, File file) throws IOException {
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setRequestProperty("Accept-Encoding", "identity");
      conn.addRequestProperty("Connection", "keep-alive");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(10000);
      if (file.exists() && file.length() > 0) {
        // 断点续传
        conn.setRequestProperty("Range", "bytes=" + file.length() + "-");
      }
      return conn;
    }

    private String getFileName(DownloadAgent.DownloadItem downloadItem) {
      String fileName;
      if (downloadItem.sign != null) {
        fileName = downloadItem.sign + ".apk.tmp";
      } else {
        fileName = UpdateUtils.getSHA1(downloadItem.url) + ".apk.tmp";
      }
      return fileName;
    }


    private void checkMd5(File newFile)
        throws RemoteException {
      if (mDownloadItem.sign != null
          && !mDownloadItem.sign.equalsIgnoreCase(UpdateUtils
              .getFileSHA1(newFile))) {
        sClients.get(mDownloadItem).send(
            Message.obtain(null, MSG_COMPLETE,
                DOWNLOAD_COMPLETE_FAIL, 0));
        DownloadTool.clearCache(mContext, sPairCache, sClients, mNotificationId);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setTicker(mContext.getString(R.string.update_download_failed))
            .setContentTitle(mContext.getString(R.string.update_download_failed))
            .setWhen(System.currentTimeMillis());
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        sNotificationManager.notify(mNotificationId, notification);
      }
    }

    private void errDownload(Exception e) {
      Timber.e("can not install. " + e.getMessage());
      if (mListener != null)
        mListener.onError(mNotificationId, e);
    }
  }
}
