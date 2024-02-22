package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.pool.GlideTrace;
import java.io.File;
import java.util.List;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from cache files
 * containing downsampled/transformed resource data.
 */
class ResourceCacheGenerator implements DataFetcherGenerator, DataFetcher.DataCallback<Object> {

  private final FetcherReadyCallback cb;
  private final DecodeHelper<?> helper;

  private int sourceIdIndex;
  private int resourceClassIndex = -1;
  private Key sourceKey;
  private List<ModelLoader<File, ?>> modelLoaders;
  private int modelLoaderIndex;
  private volatile LoadData<?> loadData;
  // PMD is wrong here, this File must be an instance variable because it may be used across
  // multiple calls to startNext.
  @SuppressWarnings("PMD.SingularField")
  private File cacheFile;

  private ResourceCacheKey currentKey;

  ResourceCacheGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }

  // See TODO below.
  @SuppressWarnings("PMD.CollapsibleIfStatements")
  @Override
  public boolean startNext() {
    GlideTrace.beginSection("ResourceCacheGenerator.startNext");
    try {
      // 获取所有的支持的 ModelLoader 中的 LoadData 对象的 Keys。
      List<Key> sourceIds = helper.getCacheKeys();
      if (sourceIds.isEmpty()) {
        return false;
      }
      // 获取所有的可用的 Transcoder 转换后的数据的 Class 对象（我们的 demo 中是 Drawable）
      List<Class<?>> resourceClasses = helper.getRegisteredResourceClasses();
      // 如果没有可用的 Transcoder 直接抛出异常。
      if (resourceClasses.isEmpty()) {
        if (File.class.equals(helper.getTranscodeClass())) {
          return false;
        }
        throw new IllegalStateException(
            "Failed to find any load path from "
                + helper.getModelClass()
                + " to "
                + helper.getTranscodeClass());
      }
      // 遍历所有的 ModelLoader 和 ResourceClasses 对象生成的 key，通过这个 key 去查找到一个可用的缓存文件对应的 ModelLoader。
      while (modelLoaders == null || !hasNextModelLoader()) {
        resourceClassIndex++;
        if (resourceClassIndex >= resourceClasses.size()) {
          sourceIdIndex++;
          if (sourceIdIndex >= sourceIds.size()) {
            return false;
          }
          resourceClassIndex = 0;
        }
        // 获取对应的 ModelLoader 中的 Key
        Key sourceId = sourceIds.get(sourceIdIndex);
        // 获取对应 Transcoder 输出的 class
        Class<?> resourceClass = resourceClasses.get(resourceClassIndex);
        // 获取裁剪方式的 Transformation
        Transformation<?> transformation = helper.getTransformation(resourceClass);
        // PMD.AvoidInstantiatingObjectsInLoops Each iteration is comparatively expensive anyway,
        // we only run until the first one succeeds, the loop runs for only a limited
        // number of iterations on the order of 10-20 in the worst case.
        // 通过上面的参数生成各种 Key。
        currentKey =
            new ResourceCacheKey( // NOPMD AvoidInstantiatingObjectsInLoops
                helper.getArrayPool(),
                sourceId,
                helper.getSignature(),
                helper.getWidth(),
                helper.getHeight(),
                transformation,
                resourceClass,
                helper.getOptions());
        // 通过生成的 Key 从 DiskLruCache 中去查找缓存文件.
        cacheFile = helper.getDiskCache().get(currentKey);
        if (cacheFile != null) {
          // 缓存文件不为空，去查找能够处理 File 类型的 ModelLoaders。
          sourceKey = sourceId;
          modelLoaders = helper.getModelLoaders(cacheFile);
          modelLoaderIndex = 0;
        }
      }

      loadData = null;
      boolean started = false;
      // 遍历找到的所有的 ModelLoader，找到一个可用的去加载 File 缓存文件。
      while (!started && hasNextModelLoader()) {
        ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
        loadData =
            modelLoader.buildLoadData(
                cacheFile, helper.getWidth(), helper.getHeight(), helper.getOptions());
        if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
          started = true;
          //通过 ModelLoader 中 loadData 中的 fetcher 去加载缓存的 File
          loadData.fetcher.loadData(helper.getPriority(), this);
        }
      }
      // 最后的返回结果表示是否拿到可用的缓存.
      return started;

      // 遍历所有的可用的 ModelLoader 和 Trascoder 最后的输出 Resource 的 Class 对象生成的 Key，
      // 然后通过这个 Key 从本地缓存中去查找对应的文件，如果有查找到对应的文件，然后去查找处理 File 类型的
      // ModelLoader，然后通过 ModelLoader 去加载对应的 File。

    } finally {
      GlideTrace.endSection();
    }
  }

  private boolean hasNextModelLoader() {
    return modelLoaderIndex < modelLoaders.size();
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  @Override
  public void onDataReady(Object data) {
    cb.onDataFetcherReady(
        sourceKey, data, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE, currentKey);
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    cb.onDataFetcherFailed(currentKey, e, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE);
  }
}
