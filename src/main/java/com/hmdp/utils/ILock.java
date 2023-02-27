package com.hmdp.utils;

/**
 * @Description: ILock
 * @Author cheng
 * @Date: 2023/2/11 22:52
 * @Version 1.0
 */
public interface ILock {

    boolean tryLock(long timeoutSec);

    void unLock();
}
