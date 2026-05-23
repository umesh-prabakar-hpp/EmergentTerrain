package com.emergentterrain.secrets;

import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-process, TTL-bounded cache around another {@link SecretSource}.
 *
 * <p>Faithful to the Hermes design: the fetched secrets are cached <b>in memory
 * only</b> (never written to disk — caching to disk would defeat the point of a
 * vault), so repeated fetches within {@code ttlSeconds} don't re-invoke the
 * vault CLI. Within a single JVM run the post-processor fetches once at startup;
 * the cache is the guard for any later runtime re-fetch (e.g. a future secret
 * refresh) so it can't hammer the vault.
 *
 * <p>A {@code ttlSeconds <= 0} disables caching (every call re-fetches). Failed
 * delegate fetches are never cached — the next call retries.
 */
class CachingSecretSource implements SecretSource {

    private final SecretSource delegate;
    private final long ttlMillis;
    private final LongSupplier clock;

    private volatile Map<String, String> cached;
    private volatile long fetchedAtMillis = Long.MIN_VALUE;

    CachingSecretSource(SecretSource delegate, int ttlSeconds) {
        this(delegate, ttlSeconds, System::currentTimeMillis);
    }

    /** Test seam: inject the clock so TTL expiry is deterministic. */
    CachingSecretSource(SecretSource delegate, int ttlSeconds, LongSupplier clock) {
        this.delegate = delegate;
        this.ttlMillis = Math.max(0L, (long) ttlSeconds * 1000L);
        this.clock = clock;
    }

    @Override
    public String name() {
        return delegate.name() + " [cache " + (ttlMillis / 1000) + "s]";
    }

    @Override
    public synchronized Map<String, String> fetch() throws Exception {
        long now = clock.getAsLong();
        if (cached != null && (now - fetchedAtMillis) < ttlMillis) {
            return cached;
        }
        Map<String, String> fresh = delegate.fetch();  // not cached if this throws
        cached = fresh;
        fetchedAtMillis = now;
        return fresh;
    }
}
