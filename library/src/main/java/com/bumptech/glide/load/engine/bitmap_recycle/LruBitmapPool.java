package com.bumptech.glide.load.engine.bitmap_recycle;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.util.Synthetic;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * An {@link com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool} implementation that uses an
 * {@link com.bumptech.glide.load.engine.bitmap_recycle.LruPoolStrategy} to bucket {@link Bitmap}s
 * and then uses an LRU eviction policy to evict {@link android.graphics.Bitmap}s from the least
 * recently used bucket in order to keep the pool below a given maximum size limit.
 */
public class LruBitmapPool implements BitmapPool {
  private static final String TAG = "LruBitmapPool";
  private static final Bitmap.Config DEFAULT_CONFIG = Bitmap.Config.ARGB_8888;

  private final LruPoolStrategy strategy;
  private final Set<Bitmap.Config> allowedConfigs;
  private final long initialMaxSize;
  private final BitmapTracker tracker;

  private long maxSize;
  private long currentSize;
  private int hits;
  private int misses;
  private int puts;
  private int evictions;

  // Exposed for testing only.
  LruBitmapPool(long maxSize, LruPoolStrategy strategy, Set<Bitmap.Config> allowedConfigs) {
    this.initialMaxSize = maxSize;
    this.maxSize = maxSize;
    this.strategy = strategy;
    this.allowedConfigs = allowedConfigs;
    this.tracker = new NullBitmapTracker();
  }

  /**
   * Constructor for LruBitmapPool.
   *
   * @param maxSize The initial maximum size of the pool in bytes.
   */
  public LruBitmapPool(long maxSize) {
    this(maxSize, getDefaultStrategy(), getDefaultAllowedConfigs());
  }

  /**
   * Constructor for LruBitmapPool.
   *
   * @param maxSize The initial maximum size of the pool in bytes.
   * @param allowedConfigs A white listed put of {@link android.graphics.Bitmap.Config} that are
   *     allowed to be put into the pool. Configs not in the allowed put will be rejected.
   */
  // Public API.
  @SuppressWarnings("unused")
  public LruBitmapPool(long maxSize, Set<Bitmap.Config> allowedConfigs) {
    this(maxSize, getDefaultStrategy(), allowedConfigs);
  }

  /** Returns the number of cache hits for bitmaps in the pool. */
  public long hitCount() {
    return hits;
  }

  /** Returns the number of cache misses for bitmaps in the pool. */
  public long missCount() {
    return misses;
  }

  /** Returns the number of bitmaps that have been evicted from the pool. */
  public long evictionCount() {
    return evictions;
  }

  /** Returns the current size of the pool in bytes. */
  public long getCurrentSize() {
    return currentSize;
  }

  @Override
  public long getMaxSize() {
    return maxSize;
  }

  @Override
  public synchronized void setSizeMultiplier(float sizeMultiplier) {
    maxSize = Math.round(initialMaxSize * sizeMultiplier);
    evict();
  }

  @Override
  public synchronized void put(Bitmap bitmap) {
    if (bitmap == null) {
      throw new NullPointerException("Bitmap must not be null");
    }
    if (bitmap.isRecycled()) {
      throw new IllegalStateException("Cannot pool recycled bitmap");
    }
    //Bitmap 不可修改 或者 内存大小大于缓存的最大可以使用的缓存 或者 配置不允许缓存的 ， 那么回收
    if (!bitmap.isMutable()
        || strategy.getSize(bitmap) > maxSize
        || !allowedConfigs.contains(bitmap.getConfig())) {
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(
            TAG,
            "Reject bitmap from pool"
                + ", bitmap: "
                + strategy.logBitmap(bitmap)
                + ", is mutable: "
                + bitmap.isMutable()
                + ", is allowed config: "
                + allowedConfigs.contains(bitmap.getConfig()));
      }
      bitmap.recycle();
      return;
    }

    final int size = strategy.getSize(bitmap);
    // 缓存
    strategy.put(bitmap);
    tracker.add(bitmap);
    // 更新缓存的 bitmap 数量，和当前缓存的占用
    puts++;
    currentSize += size;

    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      Log.v(TAG, "Put bitmap in pool=" + strategy.logBitmap(bitmap));
    }
    dump();
    // 检查是否达到最大的缓存值，如果达到了最大的缓存值，清除最旧的 Bitmap.
    evict();
  }

  private void evict() {
    trimToSize(maxSize);
  }

  @Override
  @NonNull
  public Bitmap get(int width, int height, Bitmap.Config config) {
    Bitmap result = getDirtyOrNull(width, height, config);
    if (result != null) {
      // Bitmaps in the pool contain random data that in some cases must be cleared for an image
      // to be rendered correctly. we shouldn't force all consumers to independently erase the
      // contents individually, so we do so here. See issue #131.
      // 擦除原有的 Bitmap 中的数据
      result.eraseColor(Color.TRANSPARENT);
    } else {
      // 创建一个新的空白的 Bitmap
      result = createBitmap(width, height, config);
    }

    return result;
  }

  @NonNull
  @Override
  public Bitmap getDirty(int width, int height, Bitmap.Config config) {
    Bitmap result = getDirtyOrNull(width, height, config);
    if (result == null) {
      result = createBitmap(width, height, config);
    }
    return result;
  }

  @NonNull
  private static Bitmap createBitmap(int width, int height, @Nullable Bitmap.Config config) {
    return Bitmap.createBitmap(width, height, config != null ? config : DEFAULT_CONFIG);
  }

  @TargetApi(Build.VERSION_CODES.O)
  private static void assertNotHardwareConfig(Bitmap.Config config) {
    // Avoid short circuiting on sdk int since it breaks on some versions of Android.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    if (config == Bitmap.Config.HARDWARE) {
      throw new IllegalArgumentException(
          "Cannot create a mutable Bitmap with config: "
              + config
              + ". Consider setting Downsampler#ALLOW_HARDWARE_CONFIG to false in your"
              + " RequestOptions and/or in GlideBuilder.setDefaultRequestOptions");
    }
  }

  @Nullable
  private synchronized Bitmap getDirtyOrNull(
      int width, int height, @Nullable Bitmap.Config config) {
    assertNotHardwareConfig(config);
    // Config will be null for non public config types, which can lead to transformations naively
    // passing in null as the requested config here. See issue #194.

    // 从 SizeConfigStrategy 中获取 Bitmap 缓存
    final Bitmap result = strategy.get(width, height, config != null ? config : DEFAULT_CONFIG);
    if (result == null) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Missing bitmap=" + strategy.logBitmap(width, height, config));
      }
      // 记录未命中的数量
      misses++;
    } else {
      // 记录命中的数量
      hits++;
      // 更新缓存大小
      currentSize -= strategy.getSize(result);
      tracker.remove(result);
      normalize(result);
    }
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      Log.v(TAG, "Get bitmap=" + strategy.logBitmap(width, height, config));
    }
    dump();

    return result;
  }

  // Setting these two values provides Bitmaps that are essentially equivalent to those returned
  // from Bitmap.createBitmap.
  private static void normalize(Bitmap bitmap) {
    bitmap.setHasAlpha(true);
    maybeSetPreMultiplied(bitmap);
  }

  @TargetApi(Build.VERSION_CODES.KITKAT)
  private static void maybeSetPreMultiplied(Bitmap bitmap) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      bitmap.setPremultiplied(true);
    }
  }

  @Override
  public void clearMemory() {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, "clearMemory");
    }
    trimToSize(0);
  }

  @SuppressLint("InlinedApi")
  @Override
  public void trimMemory(int level) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, "trimMemory, level=" + level);
    }
    if ((level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        || ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            && (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))) {
      clearMemory();
    } else if ((level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        || (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)) {
      trimToSize(getMaxSize() / 2);
    }
  }

  private synchronized void trimToSize(long size) {
    // 循环清除最旧的数据，直到当前的缓存大小，小于目标大小
    while (currentSize > size) {
      // 通过 SizeConfigStrategy#removeLast() 方法移除最旧的缓存
      final Bitmap removed = strategy.removeLast();
      // TODO: This shouldn't ever happen, see #331.
      if (removed == null) {
        if (Log.isLoggable(TAG, Log.WARN)) {
          Log.w(TAG, "Size mismatch, resetting");
          dumpUnchecked();
        }
        currentSize = 0;
        return;
      }
      tracker.remove(removed);
      // 重新计算当前缓存内存
      currentSize -= strategy.getSize(removed);
      // 增加被移除的 Bitmap 数量
      evictions++;
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Evicting bitmap=" + strategy.logBitmap(removed));
      }
      dump();
      removed.recycle();
    }
  }

  private void dump() {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      dumpUnchecked();
    }
  }

  private void dumpUnchecked() {
    Log.v(
        TAG,
        "Hits="
            + hits
            + ", misses="
            + misses
            + ", puts="
            + puts
            + ", evictions="
            + evictions
            + ", currentSize="
            + currentSize
            + ", maxSize="
            + maxSize
            + "\nStrategy="
            + strategy);
  }
  //Android 3.0（API 级别 11）引入了 BitmapFactory.Options.inBitmap 字段。
  // 如果设置了此选项，那么采用 Options 对象的解码方法会在加载内容时,尝试重复使用现有 Bitmap。
  // 这意味着 Bitmap 的内存得到了重复使用，从而提高了性能，同时避免了内存分配和取消分配。但是呢，
  // 因为 Android 版本碎片化的原因，复用的条件在不同的版本不一样。
  // 根据官网的解释有两种区别：分别是 Android 4.4 之前和 Android 4.4 之后
  private static LruPoolStrategy getDefaultStrategy() {
    final LruPoolStrategy strategy;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      //从 Build.VERSION_CODES.KITKAT 开始， BitmapFactory 可以重用任何可变 Bitmap 来解码任何其他 Bitmap，
      // 只要解码 Bitmap 的内存大小 byte count 小于或等于到复用 Bitmap 的 allocated byte count 。
      // 这可能是因为固有尺寸较小，或者缩放后的尺寸（对于密度/样本大小）较小。
      strategy = new SizeConfigStrategy();
    } else {
      //在 Build.VERSION_CODES.KITKAT 之前，适用其他约束：
      //
      //正在解码的图像（无论是作为资源还是作为流）必须是 jpeg 或 png 格式。
      //仅支持相同大小的位图
      //并将 inSampleSize 设置为 1。
      //重用位图的 configuration 将覆盖 inPreferredConfig 的设置（如果设置）。
      strategy = new AttributeStrategy();
    }
    return strategy;
  }

  @TargetApi(Build.VERSION_CODES.O)
  private static Set<Bitmap.Config> getDefaultAllowedConfigs() {
    Set<Bitmap.Config> configs = new HashSet<>(Arrays.asList(Bitmap.Config.values()));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      // GIFs, among other types, end up with a native Bitmap config that doesn't map to a java
      // config and is treated as null in java code. On KitKat+ these Bitmaps can be reconfigured
      // and are suitable for re-use.
      configs.add(null);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      configs.remove(Bitmap.Config.HARDWARE);
    }
    return Collections.unmodifiableSet(configs);
  }

  private interface BitmapTracker {
    void add(Bitmap bitmap);

    void remove(Bitmap bitmap);
  }

  @SuppressWarnings("unused")
  // Only used for debugging
  private static class ThrowingBitmapTracker implements BitmapTracker {
    private final Set<Bitmap> bitmaps = Collections.synchronizedSet(new HashSet<Bitmap>());

    @Override
    public void add(Bitmap bitmap) {
      if (bitmaps.contains(bitmap)) {
        throw new IllegalStateException(
            "Can't add already added bitmap: "
                + bitmap
                + " ["
                + bitmap.getWidth()
                + "x"
                + bitmap.getHeight()
                + "]");
      }
      bitmaps.add(bitmap);
    }

    @Override
    public void remove(Bitmap bitmap) {
      if (!bitmaps.contains(bitmap)) {
        throw new IllegalStateException("Cannot remove bitmap not in tracker");
      }
      bitmaps.remove(bitmap);
    }
  }

  private static final class NullBitmapTracker implements BitmapTracker {

    @Synthetic
    NullBitmapTracker() {}

    @Override
    public void add(Bitmap bitmap) {
      // Do nothing.
    }

    @Override
    public void remove(Bitmap bitmap) {
      // Do nothing.
    }
  }
}
