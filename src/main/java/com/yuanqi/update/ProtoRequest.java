package com.yuanqi.update;

import com.android.volley.Cache;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.squareup.wire.Message;
import com.squareup.wire.ProtoAdapter;
import com.squareup.wire.Wire;
import com.yuanqi.network.ApiContext;
import com.yuanqi.network.ApiRequest;
import com.yuanqi.network.RequestManager;
import com.yuanqi.update.model.RPCResponse;

import java.util.Arrays;

import okio.ByteString;

/**
 * Volley request using proto.
 * Created by nengxiangzhou on 15/5/15.
 */
class ProtoRequest<T extends Message> extends ApiRequest<T> {
  private final Class<T> mClass;
  private final RequestManager.CacheConfig mCacheConfig;
  private final byte[] mBody;

  public ProtoRequest(ApiContext apiContext, String url, Class<T> clazz, byte[] body,
      Response.Listener<T> listener, Response.ErrorListener errorListener,
      RequestManager.CacheConfig config) {
    super(apiContext, Request.Method.POST, url, listener, errorListener);
    this.mClass = clazz;
    this.mBody = body;
    this.mCacheConfig = config;
  }

  @Override
  protected T parseResponse(NetworkResponse response) throws Exception {
    RPCResponse pbRPCResponse =
        RPCResponse.ADAPTER.decode(response.data);
    if (pbRPCResponse.success == null || !pbRPCResponse.success) {
      throw new VolleyError();
    }
    return ProtoAdapter.get(mClass).decode(Wire.get(pbRPCResponse.content, ByteString.EMPTY));
  }

  @Override
  protected Cache.Entry parseCache(NetworkResponse response) {
    Cache.Entry entry = super.parseCache(response);
    if (mCacheConfig.isPreferLocalConfig()) {
      long now = System.currentTimeMillis();
      entry.ttl = now + mCacheConfig.getTtl();
      entry.softTtl = now + mCacheConfig.getSoftTtl();
    }
    return entry;
  }

  @Override
  public String getBodyContentType() {
    return "application/protobuf";
  }

  @Override
  public byte[] getBody() {
    return mBody;
  }

  @Override
  public String getCacheKey() {
    return super.getCacheKey() + Arrays.toString(getBody()).hashCode();
  }
}
