package me.littlekey.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.squareup.wire.Wire;
import me.littlekey.base.utils.DeviceConfig;
import me.littlekey.network.ApiContext;
import me.littlekey.network.RequestManager;
import me.littlekey.update.model.RPCRequest;
import me.littlekey.update.model.UpdateRequest;
import me.littlekey.update.model.UpdateResponse;

import java.io.File;

import de.greenrobot.event.EventBus;
import okio.ByteString;
import timber.log.Timber;

/**
 * 提供自动更新功能，在 Application onCreate 中初始化,
 * 调用 {@link #update} / {@link #forceUpdate} 方法更新.
 * <p/>
 * Created by nengxiangzhou on 16/4/13.
 */
public class UpdateAgent {
  private static final long DEFAULT_TTL = 7 * 24 * 60 * 60 * 1000; // 7 days
  private static final long DEFAULT_SOFT_TTL = 5 * 60 * 1000; // 5 minutes
  private final RequestManager.CacheConfig mCacheConfig;
  private final Context mContext;
  private final ApiContext mApiContext;

  private UpdateListener mUpdateListener = null;
  private final Response.Listener<UpdateResponse> mListener =
      new Response.Listener<UpdateResponse>() {
        @Override
        public void onResponse(UpdateResponse response) {
          if (Wire.get(response.has_update, false)) {
            if (response.sign != null
                && response.sign.equalsIgnoreCase(UpdateConfig.getIgnoreMd5(mContext))
                && (!UpdateConfig.isUpdateForce())) {
              onUpdateReturn(UpdateStatus.No, response);
              return;
            }
            onUpdateReturn(UpdateStatus.Yes, response);
          } else {
            onUpdateReturn(UpdateStatus.No, response);
          }
        }
      };
  private final Response.ErrorListener mErrorListener = new Response.ErrorListener() {
    @Override
    public void onErrorResponse(VolleyError error) {
      onUpdateReturn(UpdateStatus.Timeout, null);
    }
  };
  private DownloadAgent agent;

  public UpdateAgent(Context context, ApiContext apiContext) {
    mContext = context;
    mApiContext = apiContext;
    mCacheConfig = new RequestManager.CacheConfig(true, DEFAULT_TTL, DEFAULT_SOFT_TTL);
    EventBus.getDefault().register(this);
  }

  /**
   * 强制更新，在手动更新中调用，此方法会请求服务器，检查是否有最新版本，无视版本忽略
   */
  public void forceUpdate() {
    UpdateConfig.setUpdateForce(true);
    updateInternal();
  }

  /**
   * 自动更新，在主线程中调用，此方法会请求服务器，检查是否有最新版本
   */
  public void update() {
    UpdateConfig.setUpdateForce(false);
    updateInternal();
  }

  /**
   * 设置自动更新回调接口
   * 
   * @param updateListener
   *          自动更新回调接口{@link UpdateListener}
   */
  public void setUpdateListener(UpdateListener updateListener) {
    mUpdateListener = updateListener;
  }

  private void onUpdateReturn(int status, UpdateResponse response) {
    if (mUpdateListener != null) {
      mUpdateListener.onUpdateReturned(status, response);
    }
    if (status == UpdateStatus.Yes) {
      try {
        // TODO Maybe this should do in background ?
        final File file = downloadedFile(response);
        final boolean isDownloaded = file != null;
        showUpdateDialog(response, isDownloaded);
      } catch (Exception e) {
        Timber.e("Fail to create update dialog box.", e);
      }
    }
  }

  private void updateInternal() {
    try {
      if (agent != null && agent.isDownloading()) {
        onUpdateReturn(UpdateStatus.IsUpdate, null);
        Timber.i("Is updating now.");
        Toast.makeText(mContext, R.string.update_is_downloading, Toast.LENGTH_SHORT).show();
        return;
      }
      ProtoRequest<UpdateResponse> request = new ProtoRequest<>(mApiContext, Constants.UPDATE_API,
          UpdateResponse.class, buildBody(), mListener, mErrorListener, mCacheConfig);
      request.setShouldCache(false);
      request.submit();
    } catch (Exception e) {
      Timber.e("Exception occurred in UpdateAgent.update(). ", e);
    }
  }

  private byte[] buildBody() {
    RPCRequest.Builder builder = new RPCRequest.Builder();
    UpdateRequest request = new UpdateRequest.Builder()
        .package_name(DeviceConfig.getPackageName(mContext))
        .version_code(DeviceConfig.getAppVersionCode(mContext))
        .channel(DeviceConfig.getChannel(mContext))
        .build();
    builder.content(ByteString.of(UpdateRequest.ADAPTER.encode(request)));
    return RPCRequest.ADAPTER.encode(builder.build());
  }

  /**
   * Show update dialog using {@link UpdateDialogActivity}.
   * 
   * @param response
   *          Response of update api.
   * @param isDownloaded
   *          Whether the apk has been downloaded.
   */
  private void showUpdateDialog(UpdateResponse response, boolean isDownloaded) {
    Intent intent = new Intent(mContext, UpdateDialogActivity.class);
    Bundle bundle = new Bundle();
    bundle.putSerializable(Constants.KEY_RESPONSE, response);
    bundle.putBoolean(Constants.KEY_IS_DOWNLOADED, isDownloaded);
    bundle.putBoolean(Constants.KEY_IS_FORCE,
        UpdateConfig.isUpdateForce() || Wire.get(response.force_update, false));
    intent.putExtras(bundle);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    mContext.startActivity(intent);
  }

  private File downloadedFile(UpdateResponse response) {
    String fileName = response.sign + ".apk";
    File file;
    file = UpdateUtils.getDownloadDir(mContext, new boolean[1]);
    file = new File(file, fileName);
    if (file.exists() && response.sign.equalsIgnoreCase(UpdateUtils.getFileSHA1(file))) {
      return file;
    }
    return null;
  }

  private void startDownload(UpdateResponse response) {
    agent = new DownloadAgent(mContext, DeviceConfig.getApplicationLabel(mContext), response.path);
    agent.setSign(response.sign);
    agent.start();
  }

  private void startInstall(UpdateResponse response) {
    File file;
    String fileName = response.sign + ".apk";
    file = UpdateUtils.getDownloadDir(mContext, new boolean[1]);
    file = new File(file, fileName);
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
    mContext.startActivity(intent);
  }

  public void onEvent(UpdateClickEvent event) {
    switch (event.getState()) {
      case UpdateStatus.Update:
        startDownload(event.getResponse());
        break;
      case UpdateStatus.Install:
        startInstall(event.getResponse());
        break;
      case UpdateStatus.Ignore:
        UpdateConfig.saveIgnoreMd5(mContext, event.getResponse().sign);
        break;
      default:
        break;
    }
  }
}
