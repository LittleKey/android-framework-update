package me.littlekey.update;

import me.littlekey.update.model.UpdateResponse;

/**
 * 自动更新回调接口
 * @author ntop
 *
 */
public interface UpdateListener {
	/**
	 * 自动更新回调函数
	 * @param updateStatus 返回状态，0 无更新， 1 有更新，2  没有wifi（在设置仅wifi下更新时） 3 超时 
	 * @param updateInfo 更新内容UpdateResponse对象,没有更新时返回null
	 */
	void onUpdateReturned(int updateStatus, UpdateResponse updateInfo);
}
