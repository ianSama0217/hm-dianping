package com.hmdp.utils;

public interface ILock {

    /*
     * 獲取鎖
     * 
     * @param timeoutSec 鎖的過期時間，單位秒
     * 
     * @return true表示獲取鎖成功，false表示獲取鎖失敗
     */
    boolean tryLock(long timeoutSec);

    /*
     * 釋放鎖
     */
    void unlock();
}
