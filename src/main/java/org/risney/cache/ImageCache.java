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

import org.risney.cache.policies.EvictionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <h1>Add Two Numbers!</h1> The AddNum program implements an application that
 * simply adds two given integer numbers and Prints the output on the screen.
 * <p>
 * <b>Note:</b> Giving proper comments in your program makes it more user
 * friendly and it is assumed as a high quality code.
 *
 * @author Zara Ali
 * @version 1.0
 * @since 2014-03-31
 */

public class ImageCache implements MapCache {

	private static final Logger logger = LoggerFactory.getLogger(ImageCache.class);
	private static final int DEFAUL_MAX_IMAGES = 10;

	// default to 5 megabytes
	private static final int DEFAULT_MAX_BYTES = 5242880;

	private final EvictionPolicy evictionPolicy; // required
	protected int maxImages = DEFAUL_MAX_IMAGES; // optional
	protected int maxBytes = DEFAULT_MAX_BYTES; // optional

	private volatile int curentByteSize;

	private final Map<ByteBuffer, MapCacheEntry> cache;
	private final SortedMap<MapCacheEntry, ByteBuffer> inverseCacheMap;
	private final ReadWriteLock rwLock;
	private final Lock readLock;
	private final Lock writeLock;

	/**
	 * This Class uses a builder pattern to create a cache, a HashMap, with limits on number of entries, and number iof bytes.
	 * On creation, an eviction policy is assigned, and if subsequent entries are placed into cache, the algorithm chooses which cache entry to evict
	 * class method.
	 * 
	 * @param Builder
	 *            
	 * @param int maxImages, max number of entries in cache
	 * 
	 * @param int maxSize, max number oif bytes in cache
	 *
	 * @return ImageCache This an instance of a cache.
	 */

	private ImageCache(Builder builder) {
		this.evictionPolicy = builder.evictionPolicy;
		this.maxImages = builder.maxImages;
		this.maxBytes = builder.maxBytes;
		this.cache = new HashMap<>();
		this.rwLock = new ReentrantReadWriteLock();
		this.readLock = rwLock.readLock();
		this.writeLock = rwLock.writeLock();
		this.inverseCacheMap = new ConcurrentSkipListMap<>(evictionPolicy.getComparator());
		this.curentByteSize = 0;
	}

	public EvictionPolicy getEvictionPolicy() {
		return evictionPolicy;
	}

	public int getDefaultMaxImages() {
		return DEFAUL_MAX_IMAGES;
	}

	public int getDefaultMaxBytes() {
		return DEFAULT_MAX_BYTES;
	}

	public int getMaxImages() {
		return maxImages;
	}

	public int getMaxBytes() {
		return maxBytes;
	}

	public int size() {
		return this.cache.size();
	}

	public int getNumberOfBytes() {
		return curentByteSize;
	}

	/**
	 * This is the builder method for the optional max number of images and max byte size.
	 */
	public static class Builder {
		private final EvictionPolicy evictionPolicy;
		protected int maxImages;
		protected int maxBytes;

		public Builder(EvictionPolicy evictionPolicy) {
			this.evictionPolicy = evictionPolicy;
			this.maxImages = DEFAUL_MAX_IMAGES;
			this.maxBytes = DEFAULT_MAX_BYTES;
		}

		public Builder maxImages(int maxImages) {
			this.maxImages = maxImages;
			return this;
		}

		public Builder maxBytes(int maxBytes) {
			this.maxBytes = maxBytes;
			return this;
		}

		public ImageCache build() {
			return new ImageCache(this);
		}
	}

	/**
	 * @return MapPutResult private method that uses the comparators, size and number of bytes to evaluate wether to
	 * evict an entry. This is essentially the secret sauce.
	 * current cache size, and number of entries are updated accordingly.
	 */
	
	private MapCacheEntry evict() {

		logger.debug("Current bytes in cache : {} ", curentByteSize);
		logger.debug("Number of images in cache : {} ", cache.size());

		if ((cache.size() <= maxImages) && (curentByteSize < maxBytes)) {
			logger.debug("No eviction");
			return null;
		}

		final MapCacheEntry entryToEvict = inverseCacheMap.firstKey();
		final ByteBuffer valueToEvict = inverseCacheMap.remove(entryToEvict);
		cache.remove(valueToEvict);
		curentByteSize = (curentByteSize - entryToEvict.getSize());
		if (logger.isDebugEnabled()) {
			logger.info("Evicting key {} from cache", new String(valueToEvict.array(), StandardCharsets.UTF_8));
			logger.info("Number of images now in cache : {}", cache.size());

		}

		return entryToEvict;
	}
	
	/**
	 * @return MapPutResult put a Key/Value of ByteBuffer into cache, if present, overrides current value.
	 *  hit value is recorded, and size is recorded.
	 */

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
			entry.setSize(key.capacity() + value.capacity());
			curentByteSize = curentByteSize + key.capacity() + value.capacity();
			inverseCacheMap.put(entry, key);

			return new MapPutResult(false, key, value, entry.getValue(), null, null);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * @return MapPutResult put a Key/Value of ByteBuffer into cache, if present, overrides current value.
	 * No hit value is recorded
	 */
	
	@Override
	public MapPutResult put(final ByteBuffer key, final ByteBuffer value) {
		writeLock.lock();
		try {
			// evict if we need to in order to make room for a new entry.

			final MapCacheEntry entry = new MapCacheEntry(key, value);
			final MapCacheEntry existing = cache.put(key, entry);
			entry.setSize(key.capacity() + value.capacity());

			curentByteSize = curentByteSize + key.capacity() + value.capacity();

			final MapCacheEntry evicted = evict();
			inverseCacheMap.put(entry, key);

			final ByteBuffer existingValue = (existing == null) ? null : existing.getValue();
			final ByteBuffer evictedKey = (evicted == null) ? null : evicted.getKey();
			final ByteBuffer evictedValue = (evicted == null) ? null : evicted.getValue();

			return new MapPutResult(true, key, value, existingValue, evictedKey, evictedValue);
		} finally {
			writeLock.unlock();
		}
	}

	
	/**
	 * @return boolean check to see if cache contains a key (ByteBuffer).
	 */
	
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

	/**
	 * @return get a ByteBuffer value from  ByteBuffer key.
	 */

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

	/**
	 * @return remove a ByteBuffer value from  ByteBuffer key.
	 */
	@Override
	public ByteBuffer remove(ByteBuffer key) throws IOException {
		writeLock.lock();
		try {
			final MapCacheEntry record = cache.remove(key);

			if (record == null) {
				return null;
			}
			curentByteSize = (curentByteSize - record.getSize());
			inverseCacheMap.remove(record);
			return record.getValue();
		} finally {
			writeLock.unlock();
		}
	}

}
