package org.risney.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleMapCache implements MapCache {

	private static final Logger logger = LoggerFactory.getLogger(SimpleMapCache.class);

	private final Map<ByteBuffer, MapCacheEntry> cache = new HashMap<>();
	private final SortedMap<MapCacheEntry, ByteBuffer> inverseCacheMap;

	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private final Lock readLock = rwLock.readLock();
	private final Lock writeLock = rwLock.writeLock();

	private final int maxSize;
	private final int maxMemorySize;
	private volatile int memorySize;

	public SimpleMapCache(final int maxSize, final int memorySize, final EvictionPolicy evictionPolicy) {
		// need to change to ConcurrentMap as this is modified when only the
		// readLock is held
		inverseCacheMap = new ConcurrentSkipListMap<>(evictionPolicy.getComparator());
		this.maxMemorySize = memorySize;
		this.memorySize = 0;
		this.maxSize = maxSize;
	}

	// don't need synchronized because this method is only called when the
	// writeLock is held, and all
	// public methods obtain either the read or write lock
	private MapCacheEntry evict() {
		
		logger.debug("memory size = "+memorySize);
		if ((cache.size() < maxSize) && (memorySize < maxMemorySize) ){
			return null;
		}
	
		
		
	//	if ((memorySize < maxMemorySize) ){
	//		return null;
	//	}
		

		final MapCacheEntry entryToEvict = inverseCacheMap.firstKey();
		final ByteBuffer valueToEvict = inverseCacheMap.remove(entryToEvict);
		cache.remove(valueToEvict);
		memorySize = (memorySize-entryToEvict.getSize());
		if (logger.isDebugEnabled()) {
			logger.debug("Evicting key {} from cache", new String(valueToEvict.array(), StandardCharsets.UTF_8));
		}

		return entryToEvict;
	}

	@Override
	public MapPutResult putIfAbsent(final ByteBuffer key, final ByteBuffer value) {
		writeLock.lock();
		try {
			final MapCacheEntry entry = cache.get(key);
			if (entry == null) {
				// Entry is null. Adding ...
				final MapCacheEntry evicted = evict();
				final MapCacheEntry newEntry = new MapCacheEntry(key, value);
				cache.put(key, newEntry);
				inverseCacheMap.put(newEntry, key);

				if (evicted == null) {
					return new MapPutResult(true, key, value, null, null, null);
				} else {
					return new MapPutResult(true, key, value, null, evicted.getKey(), evicted.getValue());
				}
			}

			// Entry is not null. Increment hit count and return result
			// indicating that entry was not added.
			inverseCacheMap.remove(entry);
			entry.hit();
			
			
			// Set the size
			entry.setSize(key.capacity()+value.capacity());
			memorySize = memorySize+key.capacity()+value.capacity();
			inverseCacheMap.put(entry, key);

			return new MapPutResult(false, key, value, entry.getValue(), null, null);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public MapPutResult put(final ByteBuffer key, final ByteBuffer value) {
		writeLock.lock();
		try {
			// evict if we need to in order to make room for a new entry.
			final MapCacheEntry evicted = evict();

			final MapCacheEntry entry = new MapCacheEntry(key, value);
			final MapCacheEntry existing = cache.put(key, entry);
			entry.setSize(key.capacity()+value.capacity());
			memorySize = memorySize+key.capacity()+value.capacity();
			inverseCacheMap.put(entry, key);

			
			
			
			final ByteBuffer existingValue = (existing == null) ? null : existing.getValue();
			final ByteBuffer evictedKey = (evicted == null) ? null : evicted.getKey();
			final ByteBuffer evictedValue = (evicted == null) ? null : evicted.getValue();

			return new MapPutResult(true, key, value, existingValue, evictedKey, evictedValue);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public boolean containsKey(final ByteBuffer key) {
		readLock.lock();
		try {
			final MapCacheEntry entry = cache.get(key);
			if (entry == null) {
				return false;
			}

			inverseCacheMap.remove(entry);
			entry.hit();
			inverseCacheMap.put(entry, key);

			return true;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ByteBuffer get(final ByteBuffer key) {
		readLock.lock();
		try {
			final MapCacheEntry entry = cache.get(key);
			if (entry == null) {
				return null;
			}

			inverseCacheMap.remove(entry);
			entry.hit();
			inverseCacheMap.put(entry, key);

			return entry.getValue();
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ByteBuffer remove(ByteBuffer key) throws IOException {
		writeLock.lock();
		try {
			final MapCacheEntry record = cache.remove(key);
			
			if (record == null) {
				return null;
			}
			memorySize = (memorySize-record.getSize());
			inverseCacheMap.remove(record);
			return record.getValue();
		} finally {
			writeLock.unlock();
		}
	}

}