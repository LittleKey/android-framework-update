package me.littlekey.update;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.lang.ref.WeakReference;

import timber.log.Timber;

/**
 * General download agent connect with {@link DownloadingService}.
 * <p/>
 * Created by nengxiangzhou on 16/4/13.
 */
public class DownloadAgent {
  private final Messenger mClientMessenger = new Messenger(new IncomingHandler(this));
  private final Context mContext;
  private Messenger mServiceMessenger;
  private final String mTitle;
  private final String mUrl;
  private String mSign;
  private boolean mIsDownloading = false;
  /**
   * Class for interacting with the main interface of the service.
   */
  private final ServiceConnection mConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      Timber.d("ServiceConnection.onServiceConnected");
      mServiceMessenger = new Messenger(service);
      try {
        Message msg = Message.obtain();
        msg.what = DownloadingService.MSG_DOWNLOAD;
        DownloadItem dItem = new DownloadItem(mTitle, mUrl, mSign);
        msg.setData(dItem.toBundle());
        msg.replyTo = mClientMessenger;
        mServiceMessenger.send(msg);
      } catch (RemoteException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      Timber.d("ServiceConnection.onServiceDisconnected");
      mServiceMessenger = null;
    }
  };

  /**
   * Constructor.
   *
   * @param context
   * @param title
   *          The title used for notification bar.
   * @param url
   *          URL of the resource to download.
   */
  public DownloadAgent(Context context, String title, String url) {
    mContext = context;
    mTitle = title;
    mUrl = url;
  }

  public void setSign(String sign) {
    mSign = sign;
  }

  public boolean isDownloading() {
    return mIsDownloading;
  }

  public void start() {
    Intent intent = new Intent(mContext, DownloadingService.class);
    mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
  }

  static class DownloadItem {
    public final String title;
    public final String url;
    public final String sign;

    public DownloadItem(String title, String url, String sign) {
      this.title = title;
      this.url = url;
      this.sign = sign;
    }

    public static DownloadItem fromBundle(Bundle data) {
      String title = data.getString("title");
      String url = data.getString("url");
      String sign = data.getString("sign");
      return new DownloadItem(title, url, sign);
    }

    public Bundle toBundle() {
      Bundle data = new Bundle();
      data.putString("title", title);
      data.putString("url", url);
      data.putString("sign", sign);
      return data;
    }

  }

  /**
   * Handler of incoming messages from service.
   */
  static class IncomingHandler extends Handler {
    private final WeakReference<DownloadAgent> mAgent;
    public IncomingHandler(DownloadAgent agent) {
      super();
      mAgent = new WeakReference<>(agent);
    }

    /**
     * See {@link DownloadingService.IncomingHandler} for msg format description.
     */
    @Override
    public void handleMessage(Message msg) {
      DownloadAgent agent = mAgent.get();
      if (agent == null) {
        return;
      }
      try {
        Timber.d("DownloadAgent.handleMessage(" + msg.what
            + "): ");
        switch (msg.what) {
          case DownloadingService.MSG_START:
            agent.mIsDownloading = true;
            break;
          case DownloadingService.MSG_COMPLETE:
            agent.mContext.unbindService(agent.mConnection);
            agent.mIsDownloading = false;
            break;
          case DownloadingService.MSG_STATUS:
            if (msg.arg1 == DownloadingService.DOWNLOAD_IS_DOWNLOADING) {
              agent.mIsDownloading = true;
            }
            break;
          default:
            super.handleMessage(msg);
        }
      } catch (Exception e) {
        e.printStackTrace();
        Timber.d("DownloadAgent.handleMessage(" + msg.what + "): " + e.getMessage());
      }
    }
  }
}
