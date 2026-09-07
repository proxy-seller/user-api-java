package org.proxyseller;

public class Config {
    private String key;
    private String baseUri;
    private String fingerprint;
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 30_000;

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

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        if (readTimeoutMillis < 0) {
            throw new IllegalArgumentException("readTimeoutMillis must be >= 0");
        }
        this.readTimeoutMillis = readTimeoutMillis;
    }

}
