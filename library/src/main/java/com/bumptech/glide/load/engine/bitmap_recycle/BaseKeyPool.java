package com.bumptech.glide.load.engine.bitmap_recycle;

import com.bumptech.glide.util.Util;
import java.util.Queue;

abstract class BaseKeyPool<T extends Poolable> {
  private static final int MAX_SIZE = 20;
  //在 BaseKeyPool 类中是通过一个队列 Queue 实现缓存的，对多可以缓存 20 个对象
  private final Queue<T> keyPool = Util.createQueue(MAX_SIZE);

  // // 通过 get() 方法可以得到一个 T 对象，T 对象有可能是从缓存队列中取的，也有可能是通过 create() 方法新建的
  T get() {
    T result = keyPool.poll();
    if (result == null) {
      result = create();
    }
    return result;
  }
  // 通过 offer(T key) 方法将 T 对象加入到缓存队列中，前提是缓存队列没有满
  public void offer(T key) {
    if (keyPool.size() < MAX_SIZE) {
      keyPool.offer(key);
    }
  }

  abstract T create();
}
