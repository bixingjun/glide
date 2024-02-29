package com.bumptech.glide.load.engine.bitmap_recycle;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Keys {@link android.graphics.Bitmap Bitmaps} using both {@link
 * android.graphics.Bitmap#getAllocationByteCount()} and the {@link android.graphics.Bitmap.Config}
 * returned from {@link android.graphics.Bitmap#getConfig()}.
 *
 * <p>Using both the config and the byte size allows us to safely re-use a greater variety of {@link
 * android.graphics.Bitmap Bitmaps}, which increases the hit rate of the pool and therefore the
 * performance of applications. This class works around #301 by only allowing re-use of {@link
 * android.graphics.Bitmap Bitmaps} with a matching number of bytes per pixel.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
public class SizeConfigStrategy implements LruPoolStrategy {
  private static final int MAX_SIZE_MULTIPLE = 8;

  private static final Bitmap.Config[] ARGB_8888_IN_CONFIGS;

  static {
    Bitmap.Config[] result =
        new Bitmap.Config[] {
          Bitmap.Config.ARGB_8888,
          // The value returned by Bitmaps with the hidden Bitmap config.
          null,
        };
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      result = Arrays.copyOf(result, result.length + 1);
      result[result.length - 1] = Config.RGBA_F16;
    }
    ARGB_8888_IN_CONFIGS = result;
  }

  private static final Bitmap.Config[] RGBA_F16_IN_CONFIGS = ARGB_8888_IN_CONFIGS;

  // We probably could allow ARGB_4444 and RGB_565 to decode into each other, but ARGB_4444 is
  // deprecated and we'd rather be safe.
  private static final Bitmap.Config[] RGB_565_IN_CONFIGS =
      new Bitmap.Config[] {Bitmap.Config.RGB_565};
  private static final Bitmap.Config[] ARGB_4444_IN_CONFIGS =
      new Bitmap.Config[] {Bitmap.Config.ARGB_4444};
  private static final Bitmap.Config[] ALPHA_8_IN_CONFIGS =
      new Bitmap.Config[] {Bitmap.Config.ALPHA_8};

  private final KeyPool keyPool = new KeyPool();
  private final GroupedLinkedMap<Key, Bitmap> groupedMap = new GroupedLinkedMap<>();
  private final Map<Bitmap.Config, NavigableMap<Integer, Integer>> sortedSizes = new HashMap<>();
  //NavigableMap 也就是 TreeMap 保存了图片大小和这个大小的图片有几个(数量)。当数据插入时，会按照大小排序。

  @Override
  public void put(Bitmap bitmap) {
    // 获取 Bitmap 大小
    int size = Util.getBitmapByteSize(bitmap);
    // 通过 Bitmap 配置与大小计算 Key
    Key key = keyPool.get(size, bitmap.getConfig());
    // 将 Bitmap 缓存到 GroupedLinkedMap 中
    groupedMap.put(key, bitmap);
    // 重新计算当前配置的 Bitmap 的数量。
    NavigableMap<Integer, Integer> sizes = getSizesForConfig(bitmap.getConfig());
    //当前图片大小 有几个
    Integer current = sizes.get(key.size);
    //当前大小图片加一
    sizes.put(key.size, current == null ? 1 : current + 1);
  }

  @Override
  @Nullable
  public Bitmap get(int width, int height, Bitmap.Config config) {
    int size = Util.getBitmapByteSize(width, height, config);
    // 计算出最优的 key
    Key bestKey = findBestKey(size, config);
    // 调用 GroupedLinkedMap#get() 方法获取 Bitmap
    Bitmap result = groupedMap.get(bestKey);
    if (result != null) {
      // Decrement must be called before reconfigure.
      // 更新 Bitmap Size 的记录
      decrementBitmapOfSize(bestKey.size, result);
      // 重新设置 Bitmap 中的尺寸和配置
      result.reconfigure(width, height, config);
    }
    return result;
  }

  private Key findBestKey(int size, Bitmap.Config config) {
    Key result = keyPool.get(size, config);
    // 根据传入的config，选择合适和使用的config，可能一种config合适复用多种config
    for (Bitmap.Config possibleConfig : getInConfigs(config)) {
      // 根据config，获取对应config所有大小的TreeMap
      NavigableMap<Integer, Integer> sizesForPossibleConfig = getSizesForConfig(possibleConfig);
      // 得到大小大于或者等于指定复用的size的最小值
      //ceilingKey 是 Java 中 TreeMap 类的一个方法，用于返回大于或等于给定键的最小键，如果没有这样的键，则返回 null。
      Integer possibleSize = sizesForPossibleConfig.ceilingKey(size);
      if (possibleSize != null && possibleSize <= size * MAX_SIZE_MULTIPLE) {
        if (possibleSize != size
            || (possibleConfig == null ? config != null : !possibleConfig.equals(config))) {
          //如果取出的 possibleSize 和目标 size 不相等，说明找到了最优解，则说明上面的 result 对应的 key 不是最优解，
          // 先把它放到 key 缓存池中，然后用最优的 possibleSize 和 possibleConfig 重新从 key 缓存池中生成或者获取一个 key
          keyPool.offer(result);
          // 重新用最优的size和config获取对应的最优key
          result = keyPool.get(possibleSize, possibleConfig);
        }
        //如果取出的 possibleSize 和目标 size 相等，说明上面目标 key(resul)就可能是最优的，则把当前的配置和大小更新 key
        break;
      }
    }
    return result;
  }

  @Override
  @Nullable
  public Bitmap removeLast() {
    Bitmap removed = groupedMap.removeLast();
    if (removed != null) {
      int removedSize = Util.getBitmapByteSize(removed);
      // 重新计算当前配置的 Bitmap 的数量。
      decrementBitmapOfSize(removedSize, removed);
    }
    return removed;
  }

  private void decrementBitmapOfSize(Integer size, Bitmap removed) {
    Bitmap.Config config = removed.getConfig();
    //（其中 Key 是 Bitmap 的大小， Value 是对应的数量）。
    NavigableMap<Integer, Integer> sizes = getSizesForConfig(config);
    Integer current = sizes.get(size);
    if (current == null) {
      throw new NullPointerException(
          "Tried to decrement empty size"
              + ", size: "
              + size
              + ", removed: "
              + logBitmap(removed)
              + ", this: "
              + this);
    }

    if (current == 1) {
      sizes.remove(size);
    } else {
      sizes.put(size, current - 1);
    }
  }

  private NavigableMap<Integer, Integer> getSizesForConfig(Bitmap.Config config) {
    //（其中 Key 是 Bitmap 的大小， Value 是对应的数量）。
    NavigableMap<Integer, Integer> sizes = sortedSizes.get(config);
    if (sizes == null) {
      sizes = new TreeMap<>();
      sortedSizes.put(config, sizes);
    }
    return sizes;
  }

  @Override
  public String logBitmap(Bitmap bitmap) {
    int size = Util.getBitmapByteSize(bitmap);
    return getBitmapString(size, bitmap.getConfig());
  }

  @Override
  public String logBitmap(int width, int height, Bitmap.Config config) {
    int size = Util.getBitmapByteSize(width, height, config);
    return getBitmapString(size, config);
  }

  @Override
  public int getSize(Bitmap bitmap) {
    return Util.getBitmapByteSize(bitmap);
  }

  @Override
  public String toString() {
    StringBuilder sb =
        new StringBuilder()
            .append("SizeConfigStrategy{groupedMap=")
            .append(groupedMap)
            .append(", sortedSizes=(");
    for (Map.Entry<Bitmap.Config, NavigableMap<Integer, Integer>> entry : sortedSizes.entrySet()) {
      sb.append(entry.getKey()).append('[').append(entry.getValue()).append("], ");
    }
    if (!sortedSizes.isEmpty()) {
      sb.replace(sb.length() - 2, sb.length(), "");
    }
    return sb.append(")}").toString();
  }

  @VisibleForTesting
  static class KeyPool extends BaseKeyPool<Key> {

    public Key get(int size, Bitmap.Config config) {
      Key result = get();
      result.init(size, config);
      return result;
    }

    @Override
    protected Key create() {
      return new Key(this);
    }
  }

  @VisibleForTesting
  static final class Key implements Poolable {
    private final KeyPool pool;

    @Synthetic int size;
    private Bitmap.Config config;

    public Key(KeyPool pool) {
      this.pool = pool;
    }

    @VisibleForTesting
    Key(KeyPool pool, int size, Bitmap.Config config) {
      this(pool);
      init(size, config);
    }

    public void init(int size, Bitmap.Config config) {
      this.size = size;
      this.config = config;
    }

    @Override
    public void offer() {
      pool.offer(this);
    }

    @Override
    public String toString() {
      return getBitmapString(size, config);
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Key) {
        Key other = (Key) o;
        return size == other.size && Util.bothNullOrEqual(config, other.config);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = size;
      result = 31 * result + (config != null ? config.hashCode() : 0);
      return result;
    }
  }

  @Synthetic
  static String getBitmapString(int size, Bitmap.Config config) {
    return "[" + size + "](" + config + ")";
  }

  private static Bitmap.Config[] getInConfigs(Bitmap.Config requested) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (Bitmap.Config.RGBA_F16.equals(requested)) { // NOPMD - Avoid short circuiting sdk checks.
        return RGBA_F16_IN_CONFIGS;
      }
    }

    switch (requested) {
      case ARGB_8888:
        return ARGB_8888_IN_CONFIGS;
      case RGB_565:
        return RGB_565_IN_CONFIGS;
      case ARGB_4444:
        return ARGB_4444_IN_CONFIGS;
      case ALPHA_8:
        return ALPHA_8_IN_CONFIGS;
      default:
        return new Bitmap.Config[] {requested};
    }
  }
}
