package me.littlekey.update;

import me.littlekey.update.model.UpdateResponse;

/**
 * Event for dialog button click.
 * Created by nengxiangzhou on 16/4/13.
 */
class UpdateClickEvent {
  private final UpdateResponse mResponse;
  private final int mState;

  public UpdateClickEvent(UpdateResponse response, int state) {
    this.mResponse = response;
    this.mState = state;
  }

  public int getState() {
    return mState;
  }

  public UpdateResponse getResponse() {
    return mResponse;
  }
}
