package com.yuanqi.update;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.yuanqi.update.model.UpdateResponse;

import de.greenrobot.event.EventBus;

/**
 * Activity to show update dialog.
 * <p/>
 * Created by nengxiangzhou on 16/4/13.
 */
public class UpdateDialogActivity extends Activity {
  private int mState = UpdateStatus.NotNow;
  private UpdateResponse mResponse;
  private boolean mIsIgnore = false;

  protected void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    this.requestWindowFeature(1);
    this.setContentView(R.layout.update_dialog);
    this.mResponse =
        (UpdateResponse) this.getIntent().getExtras().getSerializable(Constants.KEY_RESPONSE);
    final boolean isDownloaded =
        this.getIntent().getExtras().getBoolean(Constants.KEY_IS_DOWNLOADED, false);
    boolean force = this.getIntent().getExtras().getBoolean(Constants.KEY_IS_FORCE, false);

    View.OnClickListener listener = new View.OnClickListener() {
      public void onClick(View view) {
        if (R.id.update_btn_ok == view.getId()) {
          mState = isDownloaded ? UpdateStatus.Install : UpdateStatus.Update;
        } else if (mIsIgnore) {
          mState = UpdateStatus.Ignore;
        }

        UpdateDialogActivity.this.finish();
      }
    };
    CompoundButton.OnCheckedChangeListener checkedChangeListener =
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            mIsIgnore = isChecked;
          }
        };

    if (force) {
      this.findViewById(R.id.update_check_box).setVisibility(View.GONE);
      this.findViewById(R.id.update_btn_cancel).setVisibility(View.GONE);
    }

    this.findViewById(R.id.update_btn_ok).setOnClickListener(listener);
    this.findViewById(R.id.update_btn_cancel).setOnClickListener(listener);
    ((CheckBox) this.findViewById(R.id.update_check_box))
        .setOnCheckedChangeListener(checkedChangeListener);
    String content = UpdateUtils.toUpdateContentString(this, mResponse, isDownloaded);
    TextView contentView = (TextView) this.findViewById(R.id.update_content);
    contentView.requestFocus();
    contentView.setText(content);
  }

  protected void onDestroy() {
    EventBus.getDefault().post(new UpdateClickEvent(mResponse, mState));
    super.onDestroy();
  }
}
