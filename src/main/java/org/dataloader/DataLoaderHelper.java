package org.dataloader;

import org.dataloader.impl.CompletableFutureKit;
import org.dataloader.stats.StatisticsCollector;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.dataloader.impl.Assertions.assertState;
import static org.dataloader.impl.Assertions.nonNull;

/**
 * This helps break up the large DataLoader class functionality and it contains the logic to dispatch the
 * promises on behalf of its peer dataloader
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
class DataLoaderHelper<K, V> {

    private final DataLoader<K,V> dataLoader;
    private final Object batchLoadFunction;
    private final DataLoaderOptions loaderOptions;
    private final CacheMap<Object, CompletableFuture<V>> futureCache;
    private final List<AbstractMap.SimpleImmutableEntry<K, CompletableFuture<V>>> loaderQueue;
    private final StatisticsCollector stats;

    DataLoaderHelper(DataLoader<K,V> dataLoader, Object batchLoadFunction, DataLoaderOptions loaderOptions, CacheMap<Object, CompletableFuture<V>> futureCache, List<AbstractMap.SimpleImmutableEntry<K, CompletableFuture<V>>> loaderQueue, StatisticsCollector stats) {
        this.dataLoader = dataLoader;
        this.batchLoadFunction = batchLoadFunction;
        this.loaderOptions = loaderOptions;
        this.futureCache = futureCache;
        this.loaderQueue = loaderQueue;
        this.stats = stats;
    }

    CompletableFuture<V> load(K key) {
        synchronized (dataLoader) {
            Object cacheKey = getCacheKey(nonNull(key));
            stats.incrementLoadCount();

            boolean batchingEnabled = loaderOptions.batchingEnabled();
            boolean cachingEnabled = loaderOptions.cachingEnabled();

            if (cachingEnabled) {
                if (futureCache.containsKey(cacheKey)) {
                    stats.incrementCacheHitCount();
                    return futureCache.get(cacheKey);
                }
            }

            CompletableFuture<V> future = new CompletableFuture<>();
            if (batchingEnabled) {
                loaderQueue.add(new AbstractMap.SimpleImmutableEntry<>(key, future));
            } else {
                stats.incrementBatchLoadCountBy(1);
                // immediate execution of batch function
                future = invokeLoaderImmediately(key);
            }
            if (cachingEnabled) {
                futureCache.set(cacheKey, future);
            }
            return future;
        }
    }

    @SuppressWarnings("unchecked")
    Object getCacheKey(K key) {
        return loaderOptions.cacheKeyFunction().isPresent() ?
                loaderOptions.cacheKeyFunction().get().getKey(key) : key;
    }

    CompletableFuture<List<V>> dispatch() {
        boolean batchingEnabled = loaderOptions.batchingEnabled();
        //
        // we copy the pre-loaded set of futures ready for dispatch
        final List<K> keys = new ArrayList<>();
        final List<CompletableFuture<V>> queuedFutures = new ArrayList<>();
        synchronized (dataLoader) {
            loaderQueue.forEach(entry -> {
                keys.add(entry.getKey());
                queuedFutures.add(entry.getValue());
            });
            loaderQueue.clear();
        }
        if (!batchingEnabled || keys.size() == 0) {
            return CompletableFuture.completedFuture(emptyList());
        }
        //
        // order of keys -> values matter in data loader hence the use of linked hash map
        //
        // See https://github.com/facebook/dataloader/blob/master/README.md for more details
        //

        //
        // when the promised list of values completes, we transfer the values into
        // the previously cached future objects that the client already has been given
        // via calls to load("foo") and loadMany(["foo","bar"])
        //
        int maxBatchSize = loaderOptions.maxBatchSize();
        if (maxBatchSize > 0 && maxBatchSize < keys.size()) {
            return sliceIntoBatchesOfBatches(keys, queuedFutures, maxBatchSize);
        } else {
            return dispatchQueueBatch(keys, queuedFutures);
        }
    }

    private CompletableFuture<List<V>> sliceIntoBatchesOfBatches(List<K> keys, List<CompletableFuture<V>> queuedFutures, int maxBatchSize) {
        // the number of keys is > than what the batch loader function can accept
        // so make multiple calls to the loader
        List<CompletableFuture<List<V>>> allBatches = new ArrayList<>();
        int len = keys.size();
        int batchCount = (int) Math.ceil(len / (double) maxBatchSize);
        for (int i = 0; i < batchCount; i++) {

            int fromIndex = i * maxBatchSize;
            int toIndex = Math.min((i + 1) * maxBatchSize, len);

            List<K> subKeys = keys.subList(fromIndex, toIndex);
            List<CompletableFuture<V>> subFutures = queuedFutures.subList(fromIndex, toIndex);

            allBatches.add(dispatchQueueBatch(subKeys, subFutures));
        }
        //
        // now reassemble all the futures into one that is the complete set of results
        return CompletableFuture.allOf(allBatches.toArray(new CompletableFuture[allBatches.size()]))
                .thenApply(v -> allBatches.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList()));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<V>> dispatchQueueBatch(List<K> keys, List<CompletableFuture<V>> queuedFutures) {
        stats.incrementBatchLoadCountBy(keys.size());
        CompletionStage<List<V>> batchLoad = invokeLoader(keys);
        return batchLoad
                .toCompletableFuture()
                .thenApply(values -> {
                    assertResultSize(keys, values);

                    for (int idx = 0; idx < queuedFutures.size(); idx++) {
                        Object value = values.get(idx);
                        CompletableFuture<V> future = queuedFutures.get(idx);
                        if (value instanceof Throwable) {
                            stats.incrementLoadErrorCount();
                            future.completeExceptionally((Throwable) value);
                            // we don't clear the cached view of this entry to avoid
                            // frequently loading the same error
                        } else if (value instanceof Try) {
                            // we allow the batch loader to return a Try so we can better represent a computation
                            // that might have worked or not.
                            Try<V> tryValue = (Try<V>) value;
                            if (tryValue.isSuccess()) {
                                future.complete(tryValue.get());
                            } else {
                                stats.incrementLoadErrorCount();
                                future.completeExceptionally(tryValue.getThrowable());
                            }
                        } else {
                            V val = (V) value;
                            future.complete(val);
                        }
                    }
                    return values;
                }).exceptionally(ex -> {
                    stats.incrementBatchLoadExceptionCount();
                    for (int idx = 0; idx < queuedFutures.size(); idx++) {
                        K key = keys.get(idx);
                        CompletableFuture<V> future = queuedFutures.get(idx);
                        future.completeExceptionally(ex);
                        // clear any cached view of this key because they all failed
                        dataLoader.clear(key);
                    }
                    return emptyList();
                });
    }


    private void assertResultSize(List<K> keys, List<V> values) {
        assertState(keys.size() == values.size(), "The size of the promised values MUST be the same size as the key list");
    }


    CompletableFuture<V> invokeLoaderImmediately(K key) {
        List<K> keys = singletonList(key);
        CompletionStage<V> singleLoadCall;
        try {
            BatchLoaderEnvironment environment = loaderOptions.getBatchLoaderEnvironmentProvider().get();
            if (isMapLoader()) {
                singleLoadCall = invokeMapBatchLoader(keys, environment).thenApply(list -> list.get(0));
            } else {
                singleLoadCall = invokeListBatchLoader(keys, environment).thenApply(list -> list.get(0));
            }
            return singleLoadCall.toCompletableFuture();
        } catch (Exception e) {
            return CompletableFutureKit.failedFuture(e);
        }
    }

    CompletionStage<List<V>> invokeLoader(List<K> keys) {
        CompletionStage<List<V>> batchLoad;
        try {
            BatchLoaderEnvironment environment = loaderOptions.getBatchLoaderEnvironmentProvider().get();
            if (isMapLoader()) {
                batchLoad = invokeMapBatchLoader(keys, environment);
            } else {
                batchLoad = invokeListBatchLoader(keys, environment);
            }
        } catch (Exception e) {
            batchLoad = CompletableFutureKit.failedFuture(e);
        }
        return batchLoad;
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<List<V>> invokeListBatchLoader(List<K> keys, BatchLoaderEnvironment environment) {
        CompletionStage<List<V>> loadResult;
        if (batchLoadFunction instanceof BatchLoaderWithContext) {
            loadResult = ((BatchLoaderWithContext<K, V>) batchLoadFunction).load(keys, environment);
        } else {
            loadResult = ((BatchLoader<K, V>) batchLoadFunction).load(keys);
        }
        return nonNull(loadResult, "Your batch loader function MUST return a non null CompletionStage promise");
    }

    /*
     * Turns a map of results that MAY be smaller than the key list back into a list by mapping null
     * to missing elements.
     */

    @SuppressWarnings("unchecked")
    private CompletionStage<List<V>> invokeMapBatchLoader(List<K> keys, BatchLoaderEnvironment environment) {
        CompletionStage<Map<K, V>> loadResult;
        if (batchLoadFunction instanceof MappedBatchLoaderWithContext) {
            loadResult = ((MappedBatchLoaderWithContext<K, V>) batchLoadFunction).load(keys, environment);
        } else {
            loadResult = ((MappedBatchLoader<K, V>) batchLoadFunction).load(keys);
        }
        CompletionStage<Map<K, V>> mapBatchLoad = nonNull(loadResult, "Your batch loader function MUST return a non null CompletionStage promise");
        return mapBatchLoad.thenApply(map -> {
            List<V> values = new ArrayList<>();
            for (K key : keys) {
                V value = map.get(key);
                values.add(value);
            }
            return values;
        });
    }

    private boolean isMapLoader() {
        return batchLoadFunction instanceof MappedBatchLoader || batchLoadFunction instanceof MappedBatchLoaderWithContext;
    }
}