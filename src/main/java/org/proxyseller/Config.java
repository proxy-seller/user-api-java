package org.proxyseller;

public class Config {
    private String key;
    private String baseUri;
    private String fingerprint;
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 30_000;
    // Денежный вызов (большой MIX-заказ) сервер может делать дольше 30 с, а обрыв по таймауту
    // не отменяет оплату — поэтому деньгам свой, более длинный таймаут чтения.
    private int moneyReadTimeoutMillis = 120_000;

    // Темп запросов читается на КАЖДОМ запросе, поэтому его можно менять у живого клиента
    // (api.getConfig()) — volatile, чтобы изменение из другого потока было видно сразу.
    private volatile boolean rateLimitEnabled = true;
    private volatile int requestsPerMinute = 1000;
    private volatile long writeIntervalMillis = 1000;
    private volatile long moneyIntervalMillis = 2000;
    private volatile int maxRetries = 3;

    public Config(String key) {
        this.key = key;
    }

    /**
     * @param key API key
     * @param baseUri API root (for example {@code http://localhost:7995/personal/api/v2/}),
     *                a URI containing {@code {apiKey}}, or the complete per-key URI
     */
    public Config(String key, String baseUri) {
        this.key = key;
        this.baseUri = baseUri;
    }

    /**
     * @param key API key
     * @param baseUri API root, a URI containing {@code {apiKey}}, or the complete per-key URI
     * @param fingerprint value of the {@code X-Fingerprint} header — see
     *                    {@link Api#setFingerprint(String)}
     */
    public Config(String key, String baseUri, String fingerprint) {
        this.key = key;
        this.baseUri = baseUri;
        this.fingerprint = fingerprint;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    /**
     * Значение заголовка {@code X-Fingerprint} для {@code order/make}. Стабильный
     * идентификатор установки клиента — форму сервер не проверяет, но случайное значение
     * на процесс ломает анти-фрод и affiliate-атрибуцию, ради которых заголовок и введён.
     *
     * @param fingerprint стабильный идентификатор установки
     */
    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        if (connectTimeoutMillis < 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be >= 0");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * How long to wait for the answer of a request, default 30 000 ms; {@code 0} waits forever.
     * Money calls wait at least {@link #setMoneyReadTimeoutMillis(int) moneyReadTimeoutMillis}.
     *
     * @param readTimeoutMillis milliseconds, at least 0; default 30 000
     */
    public void setReadTimeoutMillis(int readTimeoutMillis) {
        if (readTimeoutMillis < 0) {
            throw new IllegalArgumentException("readTimeoutMillis must be >= 0");
        }
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * How long a money call waits for its answer, default 120 000 ms.
     *
     * @return milliseconds
     * @see #setMoneyReadTimeoutMillis(int)
     */
    public int getMoneyReadTimeoutMillis() {
        return moneyReadTimeoutMillis;
    }

    /**
     * How long a money call — {@code order/make}, {@code prolong/make/{type}} and
     * {@code balance/add} — waits for its answer. The server may need well over 30 seconds for a
     * large order, and giving up does not undo the payment: a timed-out money call has an
     * <b>unknown outcome</b>, see the README section "Timeouts and retries on payments".
     *
     * <p>A money call waits for the longer of this value and
     * {@link #setReadTimeoutMillis(int) readTimeoutMillis}, so raising the general timeout raises
     * it for money calls too; {@code 0} in either of them waits forever. Every other call keeps
     * {@code readTimeoutMillis}.
     *
     * @param moneyReadTimeoutMillis milliseconds, at least 0; default 120 000
     */
    public void setMoneyReadTimeoutMillis(int moneyReadTimeoutMillis) {
        if (moneyReadTimeoutMillis < 0) {
            throw new IllegalArgumentException("moneyReadTimeoutMillis must be >= 0");
        }
        this.moneyReadTimeoutMillis = moneyReadTimeoutMillis;
    }

    /**
     * Whether the client paces its own requests. On by default.
     *
     * @return {@code true} (the default) when requests are paced and HTTP 429 is retried
     * @see #setRateLimitEnabled(boolean)
     */
    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    /**
     * Turn client-side pacing on or off.
     *
     * <p>When on (the default), all requests share a sliding window of
     * {@link #setRequestsPerMinute(int) requestsPerMinute} starts per 60 seconds; write and money
     * calls go through one queue per {@link Api} instance, one at a time, spaced by
     * {@link #setWriteIntervalMillis(long) writeIntervalMillis} and
     * {@link #setMoneyIntervalMillis(long) moneyIntervalMillis}; and an HTTP 429 is retried after
     * {@code Retry-After} up to {@link #setMaxRetries(int) maxRetries} times.
     *
     * <p>{@code false} restores the behaviour without the queue exactly: no waiting and no
     * retries, an HTTP 429 fails at once. The setting is read on every request.
     *
     * @param rateLimitEnabled {@code false} to send every request immediately
     */
    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    /**
     * The most request starts allowed within any 60 seconds, default 1000.
     *
     * @return starts per 60 seconds
     * @see #setRequestsPerMinute(int)
     */
    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    /**
     * Cap on request starts within any 60 seconds — reads, writes and money calls together.
     *
     * <p>It is a sliding window, not a token bucket: a request that would be the
     * {@code requestsPerMinute + 1}-th start within 60 seconds waits until the oldest of those
     * starts is 60 seconds old. Every retry of an HTTP 429 counts as a start of its own.
     *
     * @param requestsPerMinute starts per 60 seconds, at least 1; default 1000
     */
    public void setRequestsPerMinute(int requestsPerMinute) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be >= 1");
        }
        this.requestsPerMinute = requestsPerMinute;
    }

    /**
     * The minimum time between the starts of two write or money calls, default 1000 ms.
     *
     * @return milliseconds
     * @see #setWriteIntervalMillis(long)
     */
    public long getWriteIntervalMillis() {
        return writeIntervalMillis;
    }

    /**
     * Minimum time between the start of one write or money call and the start of the next.
     *
     * <p>Write and money calls are also never in flight at the same time: the next one starts
     * only after the previous one has finished. Reads never wait for this.
     *
     * @param writeIntervalMillis milliseconds, at least 0; default 1000
     */
    public void setWriteIntervalMillis(long writeIntervalMillis) {
        if (writeIntervalMillis < 0) {
            throw new IllegalArgumentException("writeIntervalMillis must be >= 0");
        }
        this.writeIntervalMillis = writeIntervalMillis;
    }

    /**
     * The minimum time between the starts of two money calls, default 2000 ms.
     *
     * @return milliseconds
     * @see #setMoneyIntervalMillis(long)
     */
    public long getMoneyIntervalMillis() {
        return moneyIntervalMillis;
    }

    /**
     * Minimum time between the starts of two money calls — {@code order/make},
     * {@code prolong/make/{type}} and {@code balance/add}. A money call also waits
     * {@link #setWriteIntervalMillis(long) writeIntervalMillis} after any write before it, so it
     * starts after whichever of the two ends later.
     *
     * @param moneyIntervalMillis milliseconds, at least 0; default 2000
     */
    public void setMoneyIntervalMillis(long moneyIntervalMillis) {
        if (moneyIntervalMillis < 0) {
            throw new IllegalArgumentException("moneyIntervalMillis must be >= 0");
        }
        this.moneyIntervalMillis = moneyIntervalMillis;
    }

    /**
     * How many times a request answered with HTTP 429 is sent again, default 3.
     *
     * @return retries after the first attempt
     * @see #setMaxRetries(int)
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * How many times a request answered with HTTP 429 is sent again. Nothing else is ever
     * retried: not business errors, not other HTTP statuses, not network failures.
     *
     * <p>A 429 comes from the edge in front of the API: the request never reached the API, so
     * repeating it is safe even for a money call. Each retry waits {@code Retry-After} (seconds or
     * an HTTP date; 2 seconds when the header is missing or unreadable, at most 60 seconds). When
     * the retries run out, the call fails with {@link ApiException#getHttpStatus()} {@code 429}.
     *
     * @param maxRetries retries after the first attempt, at least 0 (0 = fail on the first 429);
     *                   default 3
     */
    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.maxRetries = maxRetries;
    }

}
