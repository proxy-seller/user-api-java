package org.proxyseller;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proxy-seller api (Client API v2).
 *
 * <p>The api key is a <b>path segment</b> ({@code https://proxy-seller.com/personal/api/v2/{apiKey}/...}),
 * not a header. Every response is the envelope {@code {status, data, errors}} and almost
 * always arrives with <b>HTTP 200</b> — business failures live in {@code errors}, and there
 * is no HTTP 429 for the rate limit. Failures surface here as {@link ApiException}; use
 * {@link ApiException#getErrors()} to read the whole array, because access failures come as
 * a fixed triple that only the array distinguishes.
 *
 * <p>All ids are MongoDB ObjectId <b>strings</b>. Two exceptions: the resident list id, which
 * stayed numeric (see {@link #residentList()}), and {@code rotationId}, which is a rotation
 * interval in <b>minutes</b> ({@code 0} = By Link) and never an id at all.
 *
 * <p>Every reference {@code *Id} field of {@code order/*} and {@code prolong/*} also accepts the
 * matching <b>code</b>: when the value is not a valid id and the paired {@code *Code} field is
 * empty, the server resolves it as a code. That holds for {@code countryId} (alpha-3, upper-cased),
 * {@code periodId} (lower-cased), {@code paymentId}, {@code operatorId} (tag), {@code mixId} (tag)
 * and {@code tarifId} — so a code can be passed straight into the positional argument.
 * {@code rotationId} is the exception and has no code form. {@code reference/list} only returns a
 * code for every field, published as {@code id}; the exception is a payment
 * system it returns the id alone.
 *
 * <p><b>Android is not supported.</b> Four endpoints ({@code auth/delete},
 * {@code resident/list/delete}, {@code residentsubuser/delete} and
 * {@code residentsubuser/list/delete}) are DELETE requests carrying a JSON body. The
 * Android implementation of {@code HttpURLConnection} rejects a body on DELETE
 * ({@code ProtocolException: DELETE does not support writing}), so those four calls cannot
 * work there. Only the JVM is supported.
 */
public class Api {
    private static final String BASE_URL = "https://proxy-seller.com/personal/api/v2/";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private Config config;

    private String paymentId;
    private String paymentCode;
    private String generateAuth = "N";

    /**
     * Key placed in <a href="https://proxy-seller.com/personal/api/">https://proxy-seller.com/personal/api/</a>
     *
     * @param config the configuration
     * @throws Exception if an error occurs
     */
    public Api(Config config) throws Exception {
        if (config == null || config.getKey() == null || config.getKey().isBlank()) {
            throw new Exception("Need key, placed in https://proxy-seller.com/personal/api/");
        }
        config.setBaseUri(resolveBaseUri(config.getBaseUri(), config.getKey()));
        this.config = config;
    }

    public String getPaymentId() {
        return paymentId;
    }

    /**
     * Payment system id (MongoDB ObjectId from {@code balance/payments/list}).
     *
     * <p>On {@code order/*} and {@code prolong/*} this field also accepts a payment
     * <b>code</b> ({@code balance}) — the server falls back to a code lookup when the value
     * is not a valid id. {@code balance/add} resolves ids only, in either field.
     * For the inner balance you can also use {@code setPaymentCode("balance")}.
     *
     * @param paymentId payment system ObjectId, or a payment code on order/prolong
     */
    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
        if (paymentId != null) {
            this.paymentCode = null;
        }
    }

    public String getPaymentCode() {
        return paymentCode;
    }

    /**
     * Stable payment system code, for example {@code balance}. Codes are
     * preferable to environment-specific MongoDB ids. Resolved by {@code order/*} and
     * {@code prolong/*}; {@code balance/add} needs an id — see {@link #balanceAdd(Double)}.
     *
     * <p>{@code balance/payments/list} returns only {@code id} and {@code name}, so a payment
     * code is something you have to know, not something the reference hands you.
     *
     * @param paymentCode stable payment system code
     */
    public void setPaymentCode(String paymentCode) {
        this.paymentCode = paymentCode;
        if (paymentCode != null) {
            this.paymentId = null;
        }
    }

    public String getGenerateAuth() {
        return generateAuth;
    }

    /**
     * Generate new auths Y/N, default N.
     * Note: this field is only accepted by /order/make. The /order/calc endpoint ignores it.
     *
     * @param yn Y or N
     */
    public void setGenerateAuth(String yn) {
        this.generateAuth = (Objects.equals(yn, "Y")) ? "Y" : "N";
    }

    protected byte[] requestDownload(String method, String uri) throws Exception {
        HttpResponse response = execute(method, uri, null);
        ensureSuccessful(response);
        throwIfErrorEnvelope(response);
        return response.body;
    }

    /**
     * Send request to the server.
     *
     * @param method  The HTTP method to use.
     * @param uri     The URI of the server.
     * @param options Additional options for the request.
     * @return Object The result of the request.
     * @throws Exception If an error occurs during the request.
     */
    protected Object request(String method, String uri, RequestOptions options) throws Exception {
        HttpResponse response = execute(method, uri, options);
        ensureSuccessful(response);
        return decodeEnvelopeOrRaw(response);
    }

    /**
     * Json to Map
     *
     * @param json String to decode
     * @return Map[String, Object]
     */
    public static Map<String, Object> parseJson(String json) {
        return GSON.fromJson(json, MAP_TYPE);
    }

    /**
     * Send request to the server.
     *
     * @param method method The HTTP method to use.
     * @param uri    uri The URI of the server.
     * @return Object The result of the request.
     * @throws Exception If an error occurs during the request.
     */
    protected Object request(String method, String uri) throws Exception {
        return request(method, uri, null);
    }

    /** Send a request whose successful response is raw text rather than an API envelope. */
    protected String requestRaw(String method, String uri, RequestOptions options) throws Exception {
        HttpResponse response = execute(method, uri, options);
        ensureSuccessful(response);
        throwIfErrorEnvelope(response);
        return response.bodyAsString();
    }

    private HttpResponse execute(String method, String uri, RequestOptions options) throws ApiException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(buildUrl(uri, options));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method.toUpperCase());
            connection.setConnectTimeout(config.getConnectTimeoutMillis());
            connection.setReadTimeout(config.getReadTimeoutMillis());
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");

            if (options != null && options.getJson() != null && !options.getJson().isEmpty()) {
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                byte[] jsonBytes = GSON.toJson(options.getJson()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(jsonBytes);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] body = readAll(stream);
            return new HttpResponse(status, connection.getContentType(), body);
        } catch (IOException e) {
            throw new ApiException("Request failed: " + e.getMessage(), null, null, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildUrl(String uri, RequestOptions options) {
        String relative = uri == null ? "" : uri.replaceFirst("^/+", "");
        String fullUrl = config.getBaseUri() + relative;
        if (options != null && options.getQuery() != null && !options.getQuery().isEmpty()) {
            String queryString = options.getQuery().entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .map(entry -> encodeQuery(entry.getKey()) + "=" + encodeQuery(entry.getValue()))
                    .collect(Collectors.joining("&"));
            if (!queryString.isEmpty()) {
                fullUrl += (fullUrl.contains("?") ? "&" : "?") + queryString;
            }
        }
        return fullUrl;
    }

    private static String encodeQuery(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Экранирование ОДНОГО сегмента пути. Нужно всем подстановкам в URI ({type}, ключ),
     * иначе значение вида "ipv4/../auth" уезжает как есть и меняет маршрут.
     *
     * @param value значение сегмента
     * @return значение, пригодное для подстановки в путь
     */
    private static String encodePathSegment(String value) {
        return encodeQuery(value);
    }

    private static String resolveBaseUri(String configuredBaseUri, String apiKey) {
        String encodedKey = encodePathSegment(apiKey);
        if (configuredBaseUri == null || configuredBaseUri.isBlank()) {
            return BASE_URL + encodedKey + "/";
        }

        String result = configuredBaseUri.trim()
                .replace("{apiKey}", encodedKey)
                .replace("{key}", encodedKey);
        if (!result.endsWith("/")) {
            result += "/";
        }

        // A natural local configuration points at the v2 root. Complete per-key
        // URIs remain supported for compatibility with older callers.
        if (result.matches("(?i).*/personal/api/v2/$")) {
            return result + encodedKey + "/";
        }

        // Ключ в v2 живёт В ПУТИ, а не в заголовке. Кастомный baseUri, в котором ключа нет
        // и который не является корнем v2, раньше уезжал на сервер как есть: apiKey
        // не попадал в URL вообще, и вместо понятной ошибки клиент получал 404 либо
        // тройку "Error api key" на каждом вызове.
        if (!result.contains("/" + encodedKey + "/") && !result.contains("/" + apiKey + "/")) {
            throw new IllegalArgumentException("baseUri \"" + configuredBaseUri
                    + "\" does not contain the api key. In Client API v2 the key is a path segment: pass"
                    + " the v2 root (.../personal/api/v2/), put {apiKey} into the template,"
                    + " or pass the complete per-key URI");
        }
        return result;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void ensureSuccessful(HttpResponse response) throws ApiException {
        if (response.status >= 200 && response.status < 300) {
            return;
        }
        Map<String, Object> envelope = tryParseObject(response.bodyAsString());
        if (envelope != null) {
            if (isEnvelope(envelope)) {
                throw apiExceptionFromEnvelope(envelope, response.status, response.bodyAsString());
            }
            if (envelope.containsKey("message") || envelope.containsKey("code") || envelope.containsKey("error")) {
                throw apiExceptionFromSingleError(envelope, response.status, response.bodyAsString());
            }
        }
        throw new ApiException("Request failed with HTTP " + response.status,
                response.status, response.bodyAsString(), null);
    }

    /**
     * Наш конверт распознаём по СТРОКОВОМУ status ("success"/"error").
     * Раньше хватало самого наличия ключа, и дефолтный ответ Spring
     * ({@code {"timestamp":…,"status":400,"error":"Bad Request","path":…}}, где status —
     * ЧИСЛО) диагностировался как конверт: клиент получал "Client API returned status=error"
     * вместо настоящего "Bad Request" из поля error.
     *
     * @param envelope разобранное тело ответа
     * @return является ли тело конвертом client-api
     */
    private static boolean isEnvelope(Map<String, Object> envelope) {
        return envelope != null && envelope.get("status") instanceof String;
    }

    private static Object decodeEnvelopeOrRaw(HttpResponse response) throws ApiException {
        String body = response.bodyAsString();
        Map<String, Object> envelope = tryParseObject(body);
        if (!isEnvelope(envelope)) {
            return body;
        }
        if ("success".equals(envelope.get("status"))) {
            return envelope.get("data");
        }
        Object errors = envelope.get("errors");
        if (envelope.get("data") != null && errors instanceof Collection && ((Collection<?>) errors).isEmpty()) {
            // order/prolong calculation may return an actionable warning in
            // data while deliberately leaving errors empty.
            return envelope.get("data");
        }
        throw apiExceptionFromEnvelope(envelope, response.status, body);
    }

    private static void throwIfErrorEnvelope(HttpResponse response) throws ApiException {
        String body = response.bodyAsString();
        Map<String, Object> envelope = tryParseObject(body);
        if (isEnvelope(envelope) && "error".equals(envelope.get("status"))) {
            throw apiExceptionFromEnvelope(envelope, response.status, body);
        }
    }

    private static Map<String, Object> tryParseObject(String body) {
        if (body == null || body.isBlank() || !body.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            return parseJson(body);
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            return null;
        }
    }

    private static ApiException apiExceptionFromEnvelope(Map<String, Object> envelope, int httpStatus, String body) {
        Object responseData = envelope.get("data");
        List<Map<String, Object>> errors = errorList(envelope.get("errors"));
        Map<String, Object> firstError = errors.isEmpty() ? null : errors.get(0);

        String message = firstError != null && firstError.get("message") != null
                ? String.valueOf(firstError.get("message"))
                : errorMessageFromData(responseData);
        Integer code = firstError == null ? null : integerValue(firstError.get("code"));
        Object customData = firstError == null ? null : firstError.get("customData");
        return new ApiException(message, code, customData, httpStatus, body, responseData, errors);
    }

    /**
     * Ошибок в конверте может быть несколько, и это не декорация: доступ (битый ключ /
     * IP вне allowlist / превышение лимита) сервер отдаёт фиксированной ТРОЙКОЙ с HTTP 200.
     * Поэтому массив целиком уезжает в ApiException.getErrors(), а не только errors[0].
     *
     * @param errorsValue значение поля errors из конверта
     * @return список ошибок, каждая — Map с message/code/customData
     */
    private static List<Map<String, Object>> errorList(Object errorsValue) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(errorsValue instanceof Collection)) {
            return result;
        }
        for (Object candidate : (Collection<?>) errorsValue) {
            LinkedHashMap<String, Object> error = new LinkedHashMap<>();
            if (candidate instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) candidate).entrySet()) {
                    error.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (candidate != null) {
                error.put("message", String.valueOf(candidate));
            }
            result.add(error);
        }
        return result;
    }

    private static ApiException apiExceptionFromSingleError(Map<String, Object> error, int httpStatus, String body) {
        Object messageValue = error.get("message") != null ? error.get("message") : error.get("error");
        String message = messageValue != null
                ? String.valueOf(messageValue)
                : "Client API returned HTTP " + httpStatus;
        Object customData = error.containsKey("customData") ? error.get("customData") : error.get("custom_data");
        return new ApiException(message, integerValue(error.get("code")), customData,
                httpStatus, body, null);
    }

    private static String errorMessageFromData(Object data) {
        if (data instanceof Map) {
            Object warning = ((Map<?, ?>) data).get("warning");
            if (warning != null && !String.valueOf(warning).isBlank()) {
                return String.valueOf(warning);
            }
        }
        return "Client API returned status=error";
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class HttpResponse {
        final int status;
        final String contentType;
        final byte[] body;

        private HttpResponse(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body;
        }

        private String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }


    /**
     * Get auths
     *
     * @return array Returns list auths
     */
    public List authList() throws Exception {
        return ((List) request("get", "auth/list"));
    }

    /**
     * Create login/password authorization.
     *
     * @param orderNumber  order number
     * @param generateAuth Y/N
     * @return created auth
     * @throws Exception Error
     */
    public Map authAdd(String orderNumber, String generateAuth) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("orderNumber", orderNumber);
        map.put("generateAuth", generateAuth);
        options.setJson(map);

        return (Map) (request("post", "auth/add", options));
    }

    /**
     * Create IP authorization.
     *
     * @param orderNumber order number
     * @param ip          ip address
     * @return created auth
     * @throws Exception Error
     */
    public Map authAddIp(String orderNumber, String ip) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("orderNumber", orderNumber);
        map.put("ip", ip);
        options.setJson(map);

        return (Map) (request("post", "auth/add/ip", options));
    }

    /**
     * Change authorization.
     * Replaces the v1 auth/active method: the active flag is a boolean now.
     *
     * @param id       auth id
     * @param active   active state
     * @param login    new login (optional)
     * @param password new password (optional)
     * @param ip       new ip (optional)
     * @return current auth
     * @throws Exception Error
     */
    public Map authChange(String id, Boolean active, String login, String password, String ip) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("active", active);
        map.put("login", login);
        map.put("password", password);
        map.put("ip", ip);
        options.setJson(map);

        return (Map) (request("post", "auth/change", options));
    }

    /**
     * Set auth active state.
     *
     * @param id     auth id
     * @param active active state
     * @return current auth
     * @throws Exception Error
     */
    public Map authChange(String id, Boolean active) throws Exception {
        return authChange(id, active, null, null, null);
    }

    /**
     * Delete authorization.
     *
     * @param id auth id
     * @return result
     * @throws Exception Error
     */
    public Map authDelete(String id) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        options.setJson(map);

        return (Map) (request("delete", "auth/delete", options));
    }

    /**
     * Get balance statistic.
     *
     * @return float The balance statistic value.
     * @throws Exception Error
     */
    public Double balance() throws Exception {
        return (Double) (((Map<String, Object>) request("get", "balance/get")).get("summ"));
    }

    /**
     * Replenish the balance using the payment system set with {@link #setPaymentId(String)}.
     *
     * @param summ The amount to be replenished.
     * @return String A link to the payment page.
     * @throws Exception Error
     */
    public String balanceAdd(Double summ) throws Exception {
        return balanceAdd(summ, null);
    }

    /**
     * Replenish the balance.
     *
     * <p>Unlike {@code order/*} and {@code prolong/*}, this endpoint accepts
     * <b>paymentId only</b> — it does not resolve stable payment codes. Take the id
     * from {@link #balancePaymentsList()}.
     *
     * @param summ      The amount to be replenished.
     * @param paymentId The ObjectId of the payment system from {@code balance/payments/list};
     *                  null falls back to {@link #setPaymentId(String)}.
     * @return String A link to the payment page.
     * @throws IllegalArgumentException when no paymentId is available
     * @throws Exception Error
     */
    public String balanceAdd(Double summ, String paymentId) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("summ", summ);
        map.put("paymentId", requireBalancePaymentId(paymentId));
        options.setJson(map);

        return ((Map<String, Object>) request("post", "balance/add", options)).get("url").toString();
    }

    /**
     * balance/add сверяет ТОЛЬКО paymentId со списком balance/payments/list
     * (normalizeOrderReferenceCodes здесь не вызывается вовсе, в отличие от order/* и
     * prolong/*). Раньше при заданном одном paymentCode на сервер уезжал paymentId=null,
     * и клиент получал невнятное "Incorrect payment system" вместо причины.
     *
     * @param explicitPaymentId id, переданный в вызов
     * @return непустой paymentId
     */
    private String requireBalancePaymentId(String explicitPaymentId) {
        String resolved = explicitPaymentId != null && !explicitPaymentId.isBlank()
                ? explicitPaymentId
                : this.paymentId;
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (paymentCode != null && !paymentCode.isBlank()) {
            throw new IllegalArgumentException("balance/add does not resolve paymentCode \"" + paymentCode
                    + "\": this endpoint accepts paymentId only. Take the id from balancePaymentsList()"
                    + " and pass it to balanceAdd(summ, paymentId) or setPaymentId(...)");
        }
        throw new IllegalArgumentException("paymentId is required for balance/add,"
                + " take it from balancePaymentsList()");
    }

    /**
     * Get a list of payment systems for balance replenishing.
     *
     * @return An array of payment system items.
     * @throws Exception Error
     */
    public List balancePaymentsList() throws Exception {
        return ((List) ((Map<String, Object>) request("get", "balance/payments/list")).get("items"));
    }

    /**
     * Read the auto top-up configuration and its current state.
     *
     * <p>Returned fields: {@code configured}, {@code enabled}, {@code state}
     * (NO_PAYMENT_METHOD / DISABLED / ACTIVE / PAYMENT_INVALID / PAUSED_FAILURES),
     * {@code threshold}, {@code amount}, {@code subscriptionId},
     * {@code paymentMethod} (null when no card is linked; otherwise {@code id},
     * {@code status}, {@code paymentMethod}, {@code brand}, {@code last4}, {@code exp}),
     * {@code dailyCountCap}, {@code monthlyAmountCap}, {@code failCount},
     * {@code lastAttemptAt} and {@code lastEvent} (null until the first run; otherwise
     * {@code status}, {@code amount}, {@code at}, {@code reason}).
     *
     * <p>When the feature is switched off on the server the call fails with business
     * code 49 ("Auto top-up is not available").
     *
     * @return the auto top-up state
     * @throws Exception Error
     */
    public Map balanceAutoTopupGet() throws Exception {
        return (Map) (request("get", "balance/autotopup/get"));
    }

    /**
     * Enable/disable auto top-up or update its thresholds and caps.
     *
     * <p>This is a <b>partial update</b>: fields left unset in {@code options} are not
     * sent at all and keep their stored value — see {@link AutoTopupOptions}.
     *
     * <p>The response is the same payload as {@link #balanceAutoTopupGet()}, already
     * reflecting the save, so no second request is needed. Validation lives entirely on
     * the server and runs against the merged result; rejections arrive as business codes
     * 49-56 with the allowed boundaries in {@code customData}
     * ({@code minAmount} / {@code minThreshold} / {@code minDailyCountCap}), reachable
     * through {@link ApiException#getCustomData()} and {@link ApiException#getErrors()}.
     *
     * @param options fields to change
     * @return the auto top-up state after saving
     * @throws Exception Error
     */
    public Map balanceAutoTopupSet(AutoTopupOptions options) throws Exception {
        if (options == null) {
            throw new IllegalArgumentException("options is required");
        }
        return balanceAutoTopupSet(options.toMap());
    }

    /**
     * Enable/disable auto top-up or update its thresholds and caps with a free format map.
     *
     * <p>Accepted keys: {@code enabled}, {@code threshold}, {@code amount},
     * {@code subscriptionId}, {@code dailyCountCap}, {@code monthlyAmountCap}. Null
     * values are dropped so that an omitted field never resets a stored one.
     *
     * @param settings fields to change
     * @return the auto top-up state after saving
     * @throws Exception Error
     */
    public Map balanceAutoTopupSet(Map settings) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        if (settings != null) {
            for (Object key : settings.keySet()) {
                putIfNotNull(map, String.valueOf(key), settings.get(key));
            }
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("balance/autotopup/set needs at least one field to change"
                    + " (enabled, threshold, amount, subscriptionId, dailyCountCap, monthlyAmountCap)");
        }
        options.setJson(map);
        return (Map) (request("post", "balance/autotopup/set", options));
    }

    /**
     * Switch auto top-up on or off without touching the thresholds and caps.
     *
     * @param enabled the new state
     * @return the auto top-up state after saving
     * @throws Exception Error
     */
    public Map balanceAutoTopupSet(boolean enabled) throws Exception {
        AutoTopupOptions options = new AutoTopupOptions();
        options.enabled = enabled;
        return balanceAutoTopupSet(options);
    }

    /**
     * Get necessary guides for creating an order.
     *
     * @param type The type of order (ipv4, ipv6, mobile, isp, mix, resident).
     * @return The necessary guides for creating an order.
     * @throws Exception Error
     */
    public Map referenceList(String type) throws Exception {
        return ((Map) (request("get", "reference/list/" + encodePathSegment(type))));
    }

    /**
     * Get necessary guides for creating an order (all types).
     *
     * <p>Every field is called {@code id}, and its value is a readable code rather than an
     * ObjectId; put it straight into the matching {@code *Id} request field. Per section:
     * {@code country[]} with {@code id} (the alpha-3 code, {@code "USA"}) and {@code name};
     * {@code period[]} with {@code id} (the period code, {@code "1m"}) and {@code name};
     * mobile operators with {@code id} (the operator tag, case-sensitive), {@code name} and
     * {@code rotations[{id, name}]} where that {@code id} is the rotation in <b>minutes</b>
     * ({@code 0} = {@code "By Link"}) — the one {@code id} that is a number, not a code;
     * mix {@code quantities[]} with {@code id} (the package code), {@code name} and the allowed
     * {@code quantities}; resident {@code tarifs[]} with {@code id} (the tariff code),
     * {@code name} and {@code personal}.
     *
     * <p>The one exception is {@code balance/payments/list}, where {@code id} stays a real
     * ObjectId: several payment systems share a single gateway code, so the code cannot tell
     * them apart.
     *
     * @return The necessary guides for creating an order.
     * @throws Exception Error
     */
    public Map referenceList() throws Exception {
        return ((Map) (request("get", "reference/list")));
    }

    /**
     * Calculate the order IPv4.
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcIpv4(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderCalc(prepareRegular("ipv4", countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /** Calculate an IPv4 order with explicit Uptime / High Availability selection. */
    public Map orderCalcIpv4(String countryId, String periodId, Long quantity, String authorization,
                             String coupon, String customTargetName, boolean uptime) throws Exception {
        Map request = prepareRegular("ipv4", countryId, periodId, quantity, authorization, coupon, customTargetName);
        request.put("uptime", uptime);
        return orderCalc(request);
    }

    /**
     * Calculate the order ISP.
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcIsp(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderCalc(prepareRegular("isp", countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /** Calculate an ISP order with explicit Uptime / High Availability selection. */
    public Map orderCalcIsp(String countryId, String periodId, Long quantity, String authorization,
                            String coupon, String customTargetName, boolean uptime) throws Exception {
        Map request = prepareRegular("isp", countryId, periodId, quantity, authorization, coupon, customTargetName);
        request.put("uptime", uptime);
        return orderCalc(request);
    }

    /**
     * Calculate the order MIX.
     *
     * @param mixId            MIX package ObjectId, or the package tag
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcMix(String mixId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderCalc(prepareMix(mixId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /** Explicitly calculate a MIX order by its package id. */
    public Map orderCalcMixById(String mixId, String periodId, Long quantity, String authorization,
                                String coupon, String customTargetName) throws Exception {
        return orderCalcMix(mixId, periodId, quantity, authorization, coupon, customTargetName);
    }

    /**
     * Calculate a MIX order using stable reference codes.
     *
     * @param mixCode    MIX package tag (exact match). {@code reference/list} exposes it as
     *                   {@code quantities[].id} of the {@code mix}/{@code mix_isp} section
     * @param periodCode Period code ({@code 1w}, {@code 1m}, {@code 3m}) — not returned by
     *                   {@code reference/list}, which only has {@code period[].id}/{@code name}
     * @param quantity   The quantity of the order
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcMixByCode(String mixCode, String periodCode, Long quantity) throws Exception {
        OrderOptions options = new OrderOptions();
        options.sectionCode = "mix";
        options.mixCode = mixCode;
        options.periodCode = periodCode;
        options.quantity = quantity;
        return orderCalc(options);
    }

    /**
     * Calculate the order IPv6.
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @param protocol         HTTPS or SOCKS5
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcIpv6(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName, String protocol) throws Exception {
        return orderCalc(prepareIpv6(countryId, periodId, quantity, authorization, coupon, customTargetName, protocol));
    }

    /**
     * Calculate the order Mobile.
     *
     * @param countryId     Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId      Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity      The quantity of the order
     * @param authorization IP whitelist (if need)
     * @param coupon        The coupon code
     * @param operatorId    Mobile operator ObjectId, or the operator tag
     * @param rotationId    Rotation interval in <b>minutes</b> as a decimal string
     *                      ({@code "5"}, {@code "10"}, {@code "0"} = By Link). This field
     *                      has no code form: {@code "5m"} or an ObjectId is rejected.
     *                      The value comes from {@code reference/list} as
     *                      {@code country[].operators.*[].rotations[].id}
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcMobile(String countryId, String periodId, Long quantity, String authorization, String coupon, String operatorId, String rotationId) throws Exception {
        return orderCalcMobile(countryId, periodId, quantity, authorization, coupon, operatorId, rotationId, "dedicated");
    }

    /**
     * Calculate a mobile order and explicitly select {@code shared} or
     * {@code dedicated} service.
     *
     * <p>Codes go into the positional arguments; {@code rotationId} is minutes, not a code:
     * <pre>{@code
     * api.orderCalcMobile("USA", "1m", 1L, null, null, operatorId, "5", "dedicated");
     * }</pre>
     *
     * @param countryId         Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId          Period ObjectId, or the period code ({@code 1m})
     * @param quantity          The quantity of the order
     * @param authorization     IP whitelist (if need)
     * @param coupon            The coupon code
     * @param operatorId        Mobile operator ObjectId, or the operator tag
     * @param rotationId        Rotation interval in <b>minutes</b> ({@code "0"} = By Link), no code form
     * @param mobileServiceType {@code shared} or {@code dedicated}
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcMobile(String countryId, String periodId, Long quantity, String authorization,
                               String coupon, String operatorId, String rotationId,
                               String mobileServiceType) throws Exception {
        return orderCalc(prepareMobile(countryId, periodId, quantity, authorization, coupon,
                operatorId, rotationId, mobileServiceType));
    }

    /**
     * Calculate the order Resident.
     *
     * @param tarifId Resident tariff ObjectId, or the tariff code (exact match)
     * @param coupon  The coupon code
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderCalcResident(String tarifId, String coupon) throws Exception {
        return orderCalc(prepareResident(tarifId, coupon));
    }

    /**
     * Create an order IPv4.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeIpv4(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderMake(withGenerateAuth(prepareRegular("ipv4", countryId, periodId, quantity, authorization, coupon, customTargetName)));
    }

    /** Create an IPv4 order with explicit Uptime / High Availability selection. */
    public Map orderMakeIpv4(String countryId, String periodId, Long quantity, String authorization,
                             String coupon, String customTargetName, boolean uptime) throws Exception {
        Map request = prepareRegular("ipv4", countryId, periodId, quantity, authorization, coupon, customTargetName);
        request.put("uptime", uptime);
        return orderMake(withGenerateAuth(request));
    }

    /**
     * Create an order ISP.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeIsp(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderMake(withGenerateAuth(prepareRegular("isp", countryId, periodId, quantity, authorization, coupon, customTargetName)));
    }

    /** Create an ISP order with explicit Uptime / High Availability selection. */
    public Map orderMakeIsp(String countryId, String periodId, Long quantity, String authorization,
                            String coupon, String customTargetName, boolean uptime) throws Exception {
        Map request = prepareRegular("isp", countryId, periodId, quantity, authorization, coupon, customTargetName);
        request.put("uptime", uptime);
        return orderMake(withGenerateAuth(request));
    }

    /**
     * Create an order MIX.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param mixId            MIX package ObjectId, or the package tag
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeMix(String mixId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) throws Exception {
        return orderMake(withGenerateAuth(prepareMix(mixId, periodId, quantity, authorization, coupon, customTargetName)));
    }

    /** Explicitly create a MIX order by its package id. */
    public Map orderMakeMixById(String mixId, String periodId, Long quantity, String authorization,
                                String coupon, String customTargetName) throws Exception {
        return orderMakeMix(mixId, periodId, quantity, authorization, coupon, customTargetName);
    }

    /**
     * Create a MIX order using stable reference codes.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param mixCode    MIX package tag (exact match). {@code reference/list} exposes it as
     *                   {@code quantities[].id} of the {@code mix}/{@code mix_isp} section
     * @param periodCode Period code ({@code 1w}, {@code 1m}, {@code 3m}) — not returned by
     *                   {@code reference/list}, which only has {@code period[].id}/{@code name}
     * @param quantity   The quantity of the order
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeMixByCode(String mixCode, String periodCode, Long quantity) throws Exception {
        OrderOptions options = new OrderOptions();
        options.sectionCode = "mix";
        options.mixCode = mixCode;
        options.periodCode = periodCode;
        options.quantity = quantity;
        return orderMake(options);
    }

    /**
     * Create an order IPv6.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param countryId        Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId         Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name — mandatory for ipv4/ipv6/isp, and for a
     *                         mix order that does not resolve to a package; the SDK rejects the
     *                         call locally instead of letting the server answer
     *                         {@code Incorrect goal} (code 14)
     * @param protocol         HTTPS or SOCKS5
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeIpv6(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName, String protocol) throws Exception {
        return orderMake(withGenerateAuth(prepareIpv6(countryId, periodId, quantity, authorization, coupon, customTargetName, protocol)));
    }

    /**
     * Create an order Mobile.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param countryId     Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId      Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m})
     * @param quantity      The quantity of the order
     * @param authorization IP whitelist (if need)
     * @param coupon        The coupon code
     * @param operatorId    Mobile operator ObjectId, or the operator tag
     * @param rotationId    Rotation interval in <b>minutes</b> as a decimal string
     *                      ({@code "5"}, {@code "10"}, {@code "0"} = By Link). This field
     *                      has no code form: {@code "5m"} or an ObjectId is rejected.
     *                      The value comes from {@code reference/list} as
     *                      {@code country[].operators.*[].rotations[].id}
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeMobile(String countryId, String periodId, Long quantity, String authorization, String coupon, String operatorId, String rotationId) throws Exception {
        return orderMakeMobile(countryId, periodId, quantity, authorization, coupon, operatorId, rotationId, "dedicated");
    }

    /**
     * Create a mobile order and explicitly select {@code shared} or
     * {@code dedicated} service.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * <p>Codes go into the positional arguments; {@code rotationId} is minutes, not a code:
     * <pre>{@code
     * api.orderMakeMobile("USA", "1m", 1L, null, null, operatorId, "0", "shared");
     * }</pre>
     *
     * @param countryId         Country ObjectId, or the alpha-3 country code ({@code USA})
     * @param periodId          Period ObjectId, or the period code ({@code 1m})
     * @param quantity          The quantity of the order
     * @param authorization     IP whitelist (if need)
     * @param coupon            The coupon code
     * @param operatorId        Mobile operator ObjectId, or the operator tag
     * @param rotationId        Rotation interval in <b>minutes</b> ({@code "0"} = By Link), no code form
     * @param mobileServiceType {@code shared} or {@code dedicated}
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeMobile(String countryId, String periodId, Long quantity, String authorization,
                               String coupon, String operatorId, String rotationId,
                               String mobileServiceType) throws Exception {
        return orderMake(withGenerateAuth(prepareMobile(countryId, periodId, quantity, authorization,
                coupon, operatorId, rotationId, mobileServiceType)));
    }

    /**
     * Create an order Resident.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param tarifId Resident tariff ObjectId, or the tariff code (exact match)
     * @param coupon  The coupon code
     * @return An array containing the order details
     * @throws Exception Error
     */
    public Map orderMakeResident(String tarifId, String coupon) throws Exception {
        return orderMake(prepareResident(tarifId, coupon));
    }

    protected Map prepareRegular(String sectionCode, String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putPayment(map);
        map.put("sectionCode", sectionCode);
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("customTargetName", customTargetName);
        return map;
    }

    protected Map prepareMix(String mixId, String periodId, Long quantity, String authorization,
                             String coupon, String customTargetName) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putPayment(map);
        map.put("sectionCode", "mix");
        map.put("mixId", mixId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("customTargetName", customTargetName);
        return map;
    }

    protected Map prepareIpv6(String countryId, String periodId, Long quantity, String authorization, String coupon, String customTargetName, String protocol) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putPayment(map);
        map.put("sectionCode", "ipv6");
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("customTargetName", customTargetName);
        map.put("protocol", protocol);
        return map;
    }

    protected Map prepareMobile(String countryId, String periodId, Long quantity, String authorization, String coupon, String operatorId, String rotationId) {
        return prepareMobile(countryId, periodId, quantity, authorization, coupon, operatorId,
                rotationId, "dedicated");
    }

    protected Map prepareMobile(String countryId, String periodId, Long quantity, String authorization,
                                String coupon, String operatorId, String rotationId,
                                String mobileServiceType) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putPayment(map);
        map.put("sectionCode", "mobile");
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("operatorId", operatorId);
        map.put("rotationId", rotationId);
        map.put("mobileServiceType", mobileServiceType);
        return map;
    }

    protected Map prepareResident(String tarifId, String coupon) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putPayment(map);
        map.put("sectionCode", "resident");
        map.put("tarifId", tarifId);
        map.put("coupon", coupon);
        return map;
    }

    private void putPayment(Map<String, Object> map) {
        if (paymentCode != null) {
            map.put("paymentCode", paymentCode);
        } else {
            map.put("paymentId", paymentId);
        }
    }

    /**
     * generateAuth is accepted by /order/make only, /order/calc silently drops it.
     *
     * @param json The prepared order body.
     * @return The same map with the generateAuth flag applied.
     */
    protected Map withGenerateAuth(Map json) {
        json.put("generateAuth", this.generateAuth);
        return json;
    }

    /**
     * Calculate the order.
     *
     * @param json A free format map to send to the endpoint.
     * @return The result of the order calculation.
     * @throws Exception Error
     */
    public Map orderCalc(Map json) throws Exception {
        assertTargetName(json);
        RequestOptions options = new RequestOptions();
        options.setJson(json);
        return ((Map) (request("post", "order/calc", options)));
    }

    /**
     * Repeats the client-api v1 target check: an ipv4/ipv6/isp order is rejected without a target.
     * In v1 the target could be given as targetId+targetSectionId or as free text; in v2 only
     * customTargetName remains. A mix order is exempt when mixId/mixCode is supplied — otherwise
     * the server resolves the type to ipv4 and the target becomes mandatory again.
     *
     * Checked locally so a round trip is not spent on "Incorrect goal" (code 14).
     *
     * @param json order payload
     * @throws IllegalArgumentException when the target is missing
     */
    protected void assertTargetName(Map json) {
        Object rawSection = json == null ? null : json.get("sectionCode");
        String section = rawSection == null ? null : rawSection.toString();
        if (!"ipv4".equals(section) && !"ipv6".equals(section)
                && !"isp".equals(section) && !"mix".equals(section) && !"mix_isp".equals(section)) {
            return;
        }
        if (isMixResolved(section, json)) {
            return;
        }
        if (isFilled(json.get("customTargetName"))) {
            return;
        }
        throw new IllegalArgumentException("customTargetName is required for " + section
                + " orders (client api returns \"Incorrect goal\", code 14)");
    }

    /**
     * Повторяет ClientApiService.parseMixSelection: сервер распознаёт mix не только по
     * mixId/mixCode, но и через countryId — строкой "packageId:quantity" либо
     * countryId=packageId вместе с quantity. Если mix распознан, requiresClientApiGoal
     * возвращает false и цель НЕ требуется. Раньше проверялись только mixId/mixCode, из-за
     * чего orderCalc(Map)/OrderOptions с mix через countryId блокировались локально.
     */
    private static boolean isMixResolved(String section, Map json) {
        if (!"mix".equals(section) && !"mix_isp".equals(section)) {
            return false;
        }
        if (isFilled(json.get("mixId")) || isFilled(json.get("mixCode"))) {
            return true;
        }
        Object rawCountry = json.get("countryId");
        String countryId = rawCountry == null ? "" : rawCountry.toString().trim();
        if (countryId.isEmpty()) {
            return false;
        }
        if (countryId.contains(":")) {
            return true;
        }
        Object q = json.get("quantity");
        try {
            return q != null && Long.parseLong(q.toString().trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFilled(Object value) {
        return value != null && !value.toString().trim().isEmpty();
    }

    /**
     * Calculate an order using all fields supported by Client API v2. Stable
     * code fields take precedence over their corresponding id fields.
     */
    public Map orderCalc(OrderOptions orderOptions) throws Exception {
        return orderCalc(prepareOrderOptions(orderOptions, false));
    }

    /**
     * Create an order.
     *
     * @param json A free format map to send to the endpoint.
     * @return The result of the order creation.
     * @throws Exception Error
     */
    public Map orderMake(Map json) throws Exception {
        assertTargetName(json);
        RequestOptions options = new RequestOptions();
        options.setJson(json);
        return ((Map) (request("post", "order/make", options)));
    }

    /**
     * Create an order using all fields supported by Client API v2. Stable code
     * fields take precedence over their corresponding id fields.
     */
    public Map orderMake(OrderOptions orderOptions) throws Exception {
        return orderMake(prepareOrderOptions(orderOptions, true));
    }

    private Map prepareOrderOptions(OrderOptions orderOptions, boolean makeOrder) {
        if (orderOptions == null) {
            throw new IllegalArgumentException("orderOptions is required");
        }
        Map<Object, Object> map = orderOptions.toMap(makeOrder);
        if (!map.containsKey("paymentId") && !map.containsKey("paymentCode")) {
            if (paymentCode != null) {
                map.put("paymentCode", paymentCode);
            } else if (paymentId != null) {
                map.put("paymentId", paymentId);
            }
        }
        if (makeOrder && !map.containsKey("generateAuth")) {
            map.put("generateAuth", generateAuth);
        }
        return map;
    }

    protected static Map prepareProlong(List ids, String periodId, String coupon) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("ids", ids);
        map.put("periodId", periodId);
        map.put("coupon", coupon);
        return map;
    }

    /**
     * Splits what the caller passed into addresses and ObjectIds.
     *
     * <p>Renewing by the addresses themselves is what a client actually has on hand — those are
     * the strings {@code proxy/list} returns. The server accepts them in {@code ips} and resolves
     * them into {@code ids} itself ({@code ClientApiService.resolveProlongIpsToIds}, called
     * unconditionally for both calc and make). An address always contains a dot or a colon
     * (ipv4 {@code ip}, ipv6 {@code host:port}, mobile {@code ip:portHttp:portSocks}) while an
     * ObjectId is 24 hex characters with neither, so a mixed list works too.
     *
     * @param ipsOrIds addresses, ObjectId strings, or a mix of both
     * @return index 0 — addresses, index 1 — ObjectIds; either may be empty
     */
    protected static List<List<String>> splitProlongTargets(Collection<?> ipsOrIds) {
        List<String> ips = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (ipsOrIds != null) {
            for (Object item : ipsOrIds) {
                if (item == null) {
                    continue;
                }
                String value = String.valueOf(item).trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.indexOf('.') >= 0 || value.indexOf(':') >= 0) {
                    ips.add(value);
                } else {
                    ids.add(value);
                }
            }
        }
        List<List<String>> split = new ArrayList<>();
        split.add(ips);
        split.add(ids);
        return split;
    }

    /** Routes a caller-supplied list into {@code ips}/{@code ids} on the options object. */
    private static void routeProlongTargets(ProlongOptions prolongOptions, List ipsOrIds) {
        List<List<String>> split = splitProlongTargets(ipsOrIds);
        List<String> ips = split.get(0);
        List<String> ids = split.get(1);
        // An empty ids next to ips would silently win: the server prefers ids when both are set.
        if (!ids.isEmpty()) {
            prolongOptions.ids = ids;
        }
        if (!ips.isEmpty()) {
            prolongOptions.ips = ips;
        }
    }

    private Map prepareProlong(ProlongOptions prolongOptions) {
        if (prolongOptions == null) {
            throw new IllegalArgumentException("prolongOptions is required");
        }
        Map<Object, Object> map = prolongOptions.toMap();
        if (!map.containsKey("paymentId") && !map.containsKey("paymentCode")) {
            if (paymentCode != null) {
                map.put("paymentCode", paymentCode);
            } else if (paymentId != null) {
                map.put("paymentId", paymentId);
            }
        }
        return map;
    }

    /**
     * Calculate the renewal.
     *
     * @param type      The type of the renewal (ipv4, ipv6, mobile, isp, mix).
     * @param ipsOrIds  The addresses themselves, exactly as {@code proxy/list} returns them:
     *                  {@code 1.2.3.4} for ipv4/isp/mix, {@code host:port} for ipv6,
     *                  {@code ip:portHttp:portSocks} for mobile. ObjectId strings are accepted
     *                  too, and a mixed list works — each value is routed by its shape.
     * @param periodId  Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m}).
     * @param coupon    The coupon code.
     * @return The result of the renewal calculation.
     * @throws Exception Error
     */
    public Map prolongCalc(String type, List ipsOrIds, String periodId, String coupon) throws Exception {
        ProlongOptions prolongOptions = new ProlongOptions();
        routeProlongTargets(prolongOptions, ipsOrIds);
        prolongOptions.periodId = periodId;
        prolongOptions.coupon = coupon;
        return prolongCalc(type, prolongOptions);
    }

    /** Calculate a renewal with the complete Client API v2 payload. */
    public Map prolongCalc(String type, ProlongOptions prolongOptions) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(prepareProlong(prolongOptions));
        return ((Map) (request("post", "prolong/calc/" + encodePathSegment(type), options)));
    }

    /**
     * Create a renewal order.
     * Attention! Calling this method will deduct $ from your balance!
     *
     * @param type      The type of the renewal (ipv4, ipv6, mobile, isp, mix).
     * @param ipsOrIds  The addresses themselves, exactly as {@code proxy/list} returns them — see
     *                  {@link #prolongCalc(String, List, String, String)}. ObjectId strings and
     *                  mixed lists work too.
     * @param periodId  Period ObjectId, or the period code ({@code 1w}, {@code 1m}, {@code 3m}).
     * @param coupon    The coupon code.
     * @return The result of the renewal order creation.
     * @throws Exception Error
     */
    public Map prolongMake(String type, List ipsOrIds, String periodId, String coupon) throws Exception {
        ProlongOptions prolongOptions = new ProlongOptions();
        routeProlongTargets(prolongOptions, ipsOrIds);
        prolongOptions.periodId = periodId;
        prolongOptions.coupon = coupon;
        return prolongMake(type, prolongOptions);
    }

    /**
     * Create a renewal order with the complete Client API v2 payload.
     *
     * @throws ApiException при нехватке средств — продление НЕ состоялось.
     */
    public Map prolongMake(String type, ProlongOptions prolongOptions) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(prepareProlong(prolongOptions));
        return assertProlongMade((Map) request("post", "prolong/make/" + encodePathSegment(type), options));
    }

    /**
     * При нехватке средств prolong/make отдаёт конверт status="error" с ПУСТЫМ errors[] и
     * calc-данными в data (ProlongMakeResponseClientDto.ofInsufficientFunds,
     * ClientApiService.groovy:3185) — ровно ту же форму, что легитимный warning у prolong/calc.
     * Из-за этого decodeEnvelopeOrRaw возвращал такие данные как успех, и несостоявшееся
     * продление выглядело как состоявшееся. Успех определяется непустым orderId
     * (ProlongMakeDataClientDto), провал — полем warning.
     *
     * order/make этим не страдает: у OrderMakeResponseClientDto только ofSuccess/ofError,
     * и при ошибке errors[] всегда заполнен.
     */
    private static Map assertProlongMade(Map data) throws ApiException {
        if (data == null) {
            return data;
        }
        Object orderId = data.get("orderId");
        if (orderId != null && !orderId.toString().trim().isEmpty()) {
            return data;
        }
        Object warning = data.get("warning");
        String message = warning == null ? "" : warning.toString().trim();
        if (message.isEmpty()) {
            message = "prolong/make did not create an order (insufficient funds)";
        }
        // (message, businessCode, customData, httpStatus, responseBody, responseData)
        throw new ApiException(message, 0, null, 200, null, data);
    }

    /**
     * Get the list of proxies of a certain type.
     *
     * @param type The type of proxies (ipv4, ipv6, mobile, isp, mix, resident).
     * @return The list of proxies.
     * @throws Exception Error
     */
    public Map proxyList(String type) throws Exception {
        return proxyList(type, null, null, null, null, null, null);
    }

    /**
     * Get the list of proxies of a certain type with filters.
     *
     * @param type    The type of proxies (ipv4, ipv6, mobile, isp, mix, resident).
     * @param latest  Y/N, only the latest order
     * @param orderId Filter by order id (MongoDB ObjectId string, not a number)
     * @param country Filter by country code
     * @param ends    Filter by expiration
     * @param page    Page number
     * @param perPage Items per page
     * @return The list of proxies.
     * @throws Exception Error
     */
    public Map proxyList(String type, String latest, String orderId, String country, String ends, Integer page, Integer perPage) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "latest", latest);
        putIfNotNull(map, "orderId", orderId);
        putIfNotNull(map, "country", country);
        putIfNotNull(map, "ends", ends);
        putIfNotNull(map, "page", page);
        putIfNotNull(map, "per_page", perPage);
        options.setQuery(map);

        return ((Map) (request("get", "proxy/list/" + encodePathSegment(type), options)));
    }

    /**
     * Get the list of all proxies, any type.
     *
     * @return The list of proxies.
     * @throws Exception Error
     */
    public Map proxyList() throws Exception {
        return ((Map) (request("get", "proxy/list")));
    }

    /**
     * Export proxies of a certain type in TXT or CSV format.
     *
     * @param type  The type of proxies (ipv4, ipv6, mobile, isp, mix).
     * @param ext   txt/csv
     * @param proto HTTPS/SOCKS
     * @param listId only for resident, if not set - will return ip from all sheets
     * @return String The exported proxies in the specified format.
     * @throws Exception Error
     */
    public String proxyDownload(String type, String ext, String proto, String listId) throws Exception {
        return proxyDownload(type, ext, proto, listId, null, null, null);
    }

    /**
     * Export proxies of a certain type in TXT or CSV format, with filters.
     *
     * @param type       The type of proxies (ipv4, ipv6, mobile, isp, mix, resident, subresident).
     * @param ext        txt/csv
     * @param proto      HTTPS/SOCKS
     * @param listId     only for resident, if not set - will return ip from all sheets
     * @param packageKey Subpackage key. Honoured by {@code subresident} only — the literal
     *                   {@code resident} route ignores it and exports the parent package.
     * @param country    Filter by country code
     * @param ends       Filter by expiration
     * @return String The exported proxies in the specified format.
     * @throws IllegalArgumentException when packageKey is combined with type {@code resident}
     * @throws Exception Error
     */
    public String proxyDownload(String type, String ext, String proto, String listId, String packageKey, String country, String ends) throws Exception {
        // На маршруте /proxy/download/resident сервер (ClientApiService.getProxyDownload)
        // разбирает package_key ТОЛЬКО при typeKey == "subresident". Молча отдавать выгрузку
        // родительского пакета вместо запрошенного субпакета — хуже, чем упасть здесь.
        if (packageKey != null && !packageKey.isBlank() && "resident".equalsIgnoreCase(String.valueOf(type).trim())) {
            throw new IllegalArgumentException("packageKey works on proxy/download/subresident only;"
                    + " proxy/download/resident ignores it and exports the parent package."
                    + " Call proxyDownload(\"subresident\", ...) instead");
        }
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "ext", assertExt(ext));
        putIfNotNull(map, "proto", proto);
        putIfNotNull(map, "listId", listId);
        putIfNotNull(map, "package_key", packageKey);
        putIfNotNull(map, "country", country);
        putIfNotNull(map, "ends", ends);
        options.setQuery(map);
        return requestRaw("get", "proxy/download/" + encodePathSegment(type), options);
    }

    /**
     * Export the resident proxy list.
     *
     * @param listId  The list ID
     * @param ext     txt/csv
     * @param maxLine Maximum number of lines
     * @return String The exported proxies.
     * @throws Exception Error
     */
    public String proxyDownloadResident(String listId, String ext, Integer maxLine) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "id", listId);
        putIfNotNull(map, "ext", assertExt(ext));
        putIfNotNull(map, "maxLine", maxLine);
        options.setQuery(map);
        return requestRaw("get", "proxy/download/resident", options);
    }

    /**
     * Accepted values of the {@code type} field of {@code proxy/replace} — the reason of
     * the replacement, mirroring the server enum {@code ProxyReplaceType}.
     */
    public static final List<String> PROXY_REPLACE_TYPES = Collections.unmodifiableList(Arrays.asList(
            "NOT_WORK", "INCORRECT_LOCATION", "CANT_CHANGE_NETWORK", "LOW_SPEED", "CUSTOM"));

    private static final Set<String> PROXY_REPLACE_TYPE_SET =
            Collections.unmodifiableSet(new LinkedHashSet<>(PROXY_REPLACE_TYPES));

    /**
     * Replace proxy IPs.
     *
     * <p>The proxy type is <b>not</b> passed here: the server derives it from the ids.
     * {@code type} is the <b>reason</b> of the replacement and it is required.
     *
     * @param ids     The list of IP address ids (ObjectId strings).
     * @param type    The reason of the replacement: NOT_WORK, INCORRECT_LOCATION,
     *                CANT_CHANGE_NETWORK, LOW_SPEED or CUSTOM (case insensitive).
     *                Every value except CUSTOM turns into a canned comment on the server.
     * @param comment Free form comment. Required (non-blank) when type is CUSTOM,
     *                optional otherwise.
     * @return The result of the replacement.
     * @throws IllegalArgumentException when the reason is unknown, or CUSTOM comes without a comment
     * @throws Exception Error
     */
    public Map proxyReplace(List ids, String type, String comment) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("ids", ids);
        map.put("type", assertReplaceType(type, comment));
        map.put("comment", comment);
        options.setJson(map);

        return (Map) (request("post", "proxy/replace", options));
    }

    /**
     * Повторяет серверную проверку из ClientApiService.replaceProxies:
     * ProxyReplaceType.fromString(type) делает valueOf(type.toUpperCase()), а при CUSTOM
     * дополнительно требует непустой comment (иначе ошибка "Set comment", code 503).
     * Проверяем локально, чтобы не тратить круг на заведомо отклонённый запрос.
     *
     * @param type    причина замены
     * @param comment комментарий
     * @return причина в том же виде, в который её приведёт сервер (upper case)
     */
    protected static String assertReplaceType(String type, String comment) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (!PROXY_REPLACE_TYPE_SET.contains(normalized)) {
            throw new IllegalArgumentException("type is the replacement reason, not the proxy type."
                    + " Set correct type: " + String.join(" / ", PROXY_REPLACE_TYPES));
        }
        if ("CUSTOM".equals(normalized) && (comment == null || comment.trim().isEmpty())) {
            throw new IllegalArgumentException("comment is required when type is CUSTOM");
        }
        return normalized;
    }

    /**
     * Set a comment for proxies.
     *
     * @param ids     The list of IDs for the proxies.
     * @param comment The comment to set.
     * @return Integer The count of updated proxies.
     * @throws Exception Error
     */
    public Integer proxyCommentSet(List ids, String comment) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("ids", ids);
        map.put("comment", comment);
        options.setJson(map);
        return ((Double) (((Map<String, Object>) request("post", "proxy/comment/set", options)).get("updated"))).intValue();
    }

    /**
     * Package Information. Remaining traffic, end date.
     *
     * <p>Field names here are camelCase ({@code packageKey}, {@code trafficLeft},
     * {@code expiredAt}, ...) and {@code expiredAt} is a <b>string</b> in
     * {@code dd.MM.yyyy HH:mm:ss}. The {@code residentsubuser/*} endpoints differ on both
     * counts: snake_case keys and {@code expired_at} as a PHP date object.
     *
     * @return map
     * @throws Exception Error
     */
    public Map residentPackage() throws Exception {
        return ((Map) (request("get", "resident/package")));
    }

    /**
     * Traffic consumption of the resident package.
     *
     * @param filter A free format map with the filter (may be null).
     * @return map
     * @throws Exception Error
     */
    public Map residentConsumption(Map filter) throws Exception {
        RequestOptions options = new RequestOptions();
        if (filter != null) {
            options.setJson(filter);
        }
        return ((Map) (request("post", "resident/consumption", options)));
    }

    /**
     * Detailed traffic statistics of the resident package.
     *
     * <p>The package key is <b>required</b> here and its field is named
     * {@code packageKey} (or the short alias {@code key}) — <b>not</b>
     * {@code package_key} as on the {@code residentsubuser/*} endpoints.
     * Optional filter fields: {@code login}, {@code date_start}, {@code date_end}.
     *
     * @param filter A free format map with the filter (may be null).
     * @return map
     * @throws Exception Error
     */
    public Map residentTrafficDetails(Map filter) throws Exception {
        RequestOptions options = new RequestOptions();
        if (filter != null) {
            options.setJson(filter);
        }
        return ((Map) (request("post", "resident/traffic/details", options)));
    }

    /**
     * Detailed traffic statistics of one resident package or subpackage.
     *
     * @param packageKey The package key, sent as {@code packageKey}
     * @return map
     * @throws Exception Error
     */
    public Map residentTrafficDetails(String packageKey) throws Exception {
        LinkedHashMap<Object, Object> filter = new LinkedHashMap<>();
        putIfNotNull(filter, "packageKey", packageKey);
        return residentTrafficDetails(filter);
    }

    /**
     * Database of geo locations: countries -&gt; regions -&gt; cities -&gt; ISPs.
     *
     * <p>The response is a file attachment ({@code geo.json}), plain <b>JSON</b> — not a
     * zip archive, despite what older documentation claimed. The bytes are returned as is,
     * with no envelope around them.
     *
     * @return the raw geo.json bytes
     * @throws Exception Error
     */
    public byte[] residentGeo() throws Exception {
        return requestDownload("get", "resident/geo");
    }

    /**
     * Database of ISP codes.
     *
     * <p>Also a file attachment ({@code isp.json}), plain JSON, returned as raw bytes.
     *
     * @return the raw isp.json bytes
     * @throws Exception Error
     */
    public byte[] residentGeoIsp() throws Exception {
        return requestDownload("get", "resident/geo/isp");
    }

    /**
     * Number of available IPs by geo.
     *
     * @return list
     * @throws Exception Error
     */
    public List residentGeoCount() throws Exception {
        return ((List) (request("get", "resident/geo/count")));
    }

    /**
     * List of existing ip list in a package.
     *
     * <p>Every item carries a <b>numeric</b> {@code id} (resident list ids stayed
     * {@code Long}; they are not ObjectIds like everything else in v2). Gson decodes
     * untyped JSON numbers as {@code Double}, so {@code item.get("id")} is a
     * {@code Double} such as {@code 561.0} and casting it to {@code Long} throws
     * {@code ClassCastException}. Feed the raw value straight into
     * {@link #residentListRename(Object, String)}, {@link #residentListRotation(Object, Integer)}
     * or {@link #residentListDelete(Object)} — those overloads normalize it.
     *
     * @return list
     * @throws Exception Error
     */
    public List residentList() throws Exception {
        Object data = request("get", "resident/lists");
        if (data instanceof Map && ((Map<?, ?>) data).get("items") instanceof List) {
            return (List) ((Map<?, ?>) data).get("items");
        }
        return (List) data;
    }

    /**
     * Create list in package.
     *
     * @param title     list title
     * @param whitelist comma separated ip whitelist
     * @param country   geo country
     * @param region    geo region
     * @param city      geo city
     * @param isp       geo isp
     * @return map
     * @throws Exception Error
     */
    public Map residentListAdd(String title, String whitelist, String country, String region, String city, String isp) throws Exception {
        return residentListAdd(title, whitelist, country, region, city, isp, null);
    }

    /**
     * Create list in package with a rotation interval.
     *
     * @param title     list title
     * @param whitelist comma separated ip whitelist
     * @param country   geo country
     * @param region    geo region
     * @param city      geo city
     * @param isp       geo isp
     * @param rotation  rotation in seconds (-1 sticky, 0 per request, 1-3600 seconds)
     * @return map
     * @throws Exception Error
     */
    public Map residentListAdd(String title, String whitelist, String country, String region, String city, String isp, Integer rotation) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> geo = new LinkedHashMap<>();
        geo.put("country", country);
        geo.put("region", region);
        geo.put("city", city);
        geo.put("isp", isp);

        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("whitelist", whitelist);
        map.put("geo", geo);
        putIfNotNull(map, "rotation", rotation);

        options.setJson(map);
        return (Map) (request("post", "resident/list/add", options));
    }

    /**
     * Rename list in user package.
     *
     * @param id    The list ID
     * @param title new title
     * @return map
     * @throws Exception Error
     */
    public Map residentListRename(Long id, String title) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", residentListId(id));
        map.put("title", title);
        options.setJson(map);

        return (Map) (request("post", "resident/list/rename", options));
    }

    /**
     * Rename list in user package, accepting the id exactly as {@link #residentList()}
     * returned it (a Gson {@code Double} such as {@code 561.0}, a {@code String},
     * or a {@code Long}).
     *
     * @param id    The list ID in any numeric or string form
     * @param title new title
     * @return map
     * @throws Exception Error
     */
    public Map residentListRename(Object id, String title) throws Exception {
        return residentListRename(residentListId(id), title);
    }

    /**
     * Change the rotation interval of a list.
     *
     * @param id       The list ID
     * @param rotation rotation in seconds (-1 sticky, 0 per request, 1-3600 seconds)
     * @return map
     * @throws Exception Error
     */
    public Map residentListRotation(Long id, Integer rotation) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", residentListId(id));
        map.put("rotation", rotation);
        options.setJson(map);

        return (Map) (request("post", "resident/list/rotation", options));
    }

    /**
     * Change the rotation interval of a list, accepting the id exactly as
     * {@link #residentList()} returned it.
     *
     * @param id       The list ID in any numeric or string form
     * @param rotation rotation in seconds (-1 sticky, 0 per request, 1-3600 seconds)
     * @return map
     * @throws Exception Error
     */
    public Map residentListRotation(Object id, Integer rotation) throws Exception {
        return residentListRotation(residentListId(id), rotation);
    }

    /**
     * Create the tools list for the package.
     *
     * @return map
     * @throws Exception Error
     */
    public Map residentListTools() throws Exception {
        return (Map) (request("put", "resident/list/tools"));
    }

    /**
     * Remove list from user package.
     *
     * @param id The list ID
     * @return map
     * @throws Exception Error
     */
    public Map residentListDelete(Long id) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", residentListId(id));
        options.setJson(map);

        return deleteResultMap(request("delete", "resident/list/delete", options));
    }

    /**
     * Remove list from user package, accepting the id exactly as {@link #residentList()}
     * returned it.
     *
     * @param id The list ID in any numeric or string form
     * @return map
     * @throws Exception Error
     */
    public Map residentListDelete(Object id) throws Exception {
        return residentListDelete(residentListId(id));
    }

    /**
     * Ловушка, из-за которой понадобились перегрузки с Object: id резидентских листов —
     * ЧИСЛОВОЙ (ListItemResponseDto.id это Long, а не ObjectId, как остальные id в v2),
     * а Gson разбирает нетипизированный JSON-number в Double. То есть
     * residentList().get(0).get("id") — это Double 561.0: приведение к Long роняет
     * ClassCastException, а приведение через intValue() у больших id теряет точность.
     * Нормализуем через BigDecimal, дробное значение отвергаем.
     *
     * @param id id листа в любом виде: Long/Integer/Double/BigDecimal/String
     * @return нормализованный числовой id
     */
    protected static Long residentListId(Object id) {
        if (id == null) {
            throw new IllegalArgumentException("resident list id is required");
        }
        if (id instanceof Long) {
            return (Long) id;
        }
        if (id instanceof Integer || id instanceof Short || id instanceof Byte) {
            return ((Number) id).longValue();
        }
        BigDecimal decimal;
        if (id instanceof BigDecimal) {
            decimal = (BigDecimal) id;
        } else {
            String text = (id instanceof Number ? id.toString() : String.valueOf(id)).trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("resident list id is required");
            }
            try {
                decimal = new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("resident list id must be numeric, got \"" + id + "\"");
            }
        }
        try {
            return decimal.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("resident list id must be a whole number that fits into"
                    + " a long, got \"" + id + "\"");
        }
    }

    /**
     * Create a resident subpackage.
     *
     * <p>Asymmetric field: {@code expired_at} is sent as a plain <b>string</b>, but comes
     * back in the response as a PHP date <b>object</b>
     * ({@code {"date":"2026-12-31 23:59:59.000000","timezone_type":3,"timezone":"UTC"}}).
     * Read {@code ((Map) item.get("expired_at")).get("date")}, do not expect a string.
     *
     * @param isLinkDate  link the expiration to the parent package
     * @param rotation    rotation in seconds
     * @param trafficLimit traffic limit in bytes
     * @param expiredAt   expiration date as a string
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserCreate(Boolean isLinkDate, Integer rotation, String trafficLimit, String expiredAt) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("is_link_date", isLinkDate);
        map.put("rotation", rotation);
        map.put("traffic_limit", trafficLimit);
        map.put("expired_at", expiredAt);
        options.setJson(map);

        return (Map) (request("post", "residentsubuser/create", options));
    }

    /**
     * Update a resident subpackage.
     *
     * @param packageKey   subpackage key
     * @param isLinkDate   link the expiration to the parent package
     * @param rotation     rotation in seconds
     * @param trafficLimit traffic limit
     * @param isActive     active state
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserUpdate(String packageKey, Boolean isLinkDate, Integer rotation, String trafficLimit, Boolean isActive) throws Exception {
        return residentSubUserUpdate(packageKey, isLinkDate, rotation, trafficLimit, isActive, null);
    }

    /**
     * Update a resident subpackage, including its optional expiration date.
     *
     * @param packageKey   subpackage key
     * @param isLinkDate   link the expiration to the parent package
     * @param rotation     rotation in seconds
     * @param trafficLimit traffic limit in bytes
     * @param isActive     active state
     * @param expiredAt    expiration date as a string; the response returns it as a PHP date
     *                     object ({@code date}/{@code timezone_type}/{@code timezone})
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserUpdate(String packageKey, Boolean isLinkDate, Integer rotation,
                                     String trafficLimit, Boolean isActive, String expiredAt) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        putIfNotNull(map, "is_link_date", isLinkDate);
        putIfNotNull(map, "rotation", rotation);
        putIfNotNull(map, "traffic_limit", trafficLimit);
        putIfNotNull(map, "is_active", isActive);
        putIfNotNull(map, "expired_at", expiredAt);
        options.setJson(map);

        return (Map) (request("post", "residentsubuser/update", options));
    }

    /**
     * Delete a resident subpackage.
     *
     * @param packageKey subpackage key
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserDelete(String packageKey) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        options.setJson(map);

        return deleteResultMap(request("delete", "residentsubuser/delete", options));
    }

    /**
     * List of resident subpackages.
     *
     * <p>Each item holds {@code package_key}, {@code rotation}, {@code traffic_limit},
     * {@code is_link_date}, {@code is_active}, the traffic counters, and
     * {@code expired_at} as a PHP date <b>object</b>
     * ({@code date}/{@code timezone_type}/{@code timezone}), not a string.
     *
     * @return list
     * @throws Exception Error
     */
    public List residentSubUserPackages() throws Exception {
        return ((List) (request("get", "residentsubuser/packages")));
    }

    /**
     * List of existing ip lists in a subpackage.
     *
     * <p>Like {@link #residentList()}, the {@code id} of every item is numeric and arrives
     * as a Gson {@code Double}. Pass it to the {@code Object} overloads of
     * {@link #residentSubUserListRename(String, Object, String)},
     * {@link #residentSubUserListRotation(String, Object, Integer)} and
     * {@link #residentSubUserListDelete(String, Object)}.
     *
     * @param packageKey subpackage key
     * @return list
     * @throws Exception Error
     */
    public List residentSubUserLists(String packageKey) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "package_key", packageKey);
        options.setQuery(map);

        return ((List) (request("get", "residentsubuser/lists", options)));
    }

    /**
     * Create a list inside a subpackage.
     *
     * @param packageKey subpackage key
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListAdd(String packageKey) throws Exception {
        return residentSubUserListAdd(packageKey, null, null, null, null, null);
    }

    /**
     * Create a complete list inside a resident subpackage.
     *
     * @param packageKey subpackage key
     * @param title list title
     * @param whitelist comma-separated IP whitelist
     * @param geo country/region/city/isp selection
     * @param export export settings such as ports and ext
     * @param rotation rotation in seconds
     */
    public Map residentSubUserListAdd(String packageKey, String title, String whitelist,
                                      Map geo, Map export, Integer rotation) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        putIfNotNull(map, "title", title);
        putIfNotNull(map, "whitelist", whitelist);
        putIfNotNull(map, "geo", geo);
        putIfNotNull(map, "export", export);
        putIfNotNull(map, "rotation", rotation);
        options.setJson(map);

        return (Map) (request("post", "residentsubuser/list/add", options));
    }

    /**
     * Rename a list inside a subpackage.
     *
     * @param packageKey subpackage key
     * @param id         list id (numeric, like the resident list ids)
     * @param title      new title
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListRename(String packageKey, Integer id, String title) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        map.put("id", id);
        map.put("title", title);
        options.setJson(map);

        return (Map) (request("post", "residentsubuser/list/rename", options));
    }

    /**
     * Rename a list inside a subpackage, accepting the id exactly as
     * {@link #residentSubUserLists(String)} returned it (a Gson {@code Double}).
     *
     * @param packageKey subpackage key
     * @param id         list id in any numeric or string form
     * @param title      new title
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListRename(String packageKey, Object id, String title) throws Exception {
        return residentSubUserListRename(packageKey, residentListId(id).intValue(), title);
    }

    /**
     * Change the rotation interval of a list inside a subpackage.
     *
     * @param packageKey subpackage key
     * @param id         list id (numeric, like the resident list ids)
     * @param rotation   rotation in seconds
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListRotation(String packageKey, Integer id, Integer rotation) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        map.put("id", id);
        map.put("rotation", rotation);
        options.setJson(map);

        return (Map) (request("post", "residentsubuser/list/rotation", options));
    }

    /**
     * Change the rotation interval of a list inside a subpackage, accepting the id
     * exactly as {@link #residentSubUserLists(String)} returned it.
     *
     * @param packageKey subpackage key
     * @param id         list id in any numeric or string form
     * @param rotation   rotation in seconds
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListRotation(String packageKey, Object id, Integer rotation) throws Exception {
        return residentSubUserListRotation(packageKey, residentListId(id).intValue(), rotation);
    }

    /**
     * Create the tools list inside a subpackage.
     *
     * @param packageKey subpackage key
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListTools(String packageKey) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        options.setJson(map);

        return (Map) (request("put", "residentsubuser/list/tools", options));
    }

    /**
     * Delete a list inside a subpackage.
     *
     * <p>On success {@code data} is the JSON string {@code {"status":"delete"}} rather than
     * an object; it is parsed back into a map here.
     *
     * @param packageKey subpackage key
     * @param id         list id (the server DTO declares it as a string)
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListDelete(String packageKey, String id) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("package_key", packageKey);
        map.put("id", id);
        options.setJson(map);

        return deleteResultMap(request("delete", "residentsubuser/list/delete", options));
    }

    /**
     * Delete a list inside a subpackage, accepting the id exactly as
     * {@link #residentSubUserLists(String)} returned it (a Gson {@code Double}, which
     * would otherwise be stringified as "561.0" and never match).
     *
     * @param packageKey subpackage key
     * @param id         list id in any numeric or string form
     * @return map
     * @throws Exception Error
     */
    public Map residentSubUserListDelete(String packageKey, Object id) throws Exception {
        return residentSubUserListDelete(packageKey, String.valueOf(residentListId(id)));
    }

    private static Map deleteResultMap(Object data) {
        if (data instanceof Map) {
            return (Map) data;
        }
        if (data instanceof String) {
            String value = (String) data;
            Map<String, Object> parsed = tryParseObject(value);
            if (parsed != null) {
                return parsed;
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("status", value);
            return result;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("status", data);
        return result;
    }

    /**
     * The server rejects ext with a plain text 400 instead of the usual envelope,
     * so it is validated on the client side.
     *
     * @param ext The requested file extension.
     * @return The same value when it is acceptable.
     * @throws Exception When the extension is too long or contains forbidden characters.
     */
    protected static String assertExt(String ext) throws Exception {
        if (ext == null) {
            return null;
        }
        if (ext.length() > 250) {
            throw new Exception("ext is too long (max 250)");
        }
        if (ext.contains("\r") || ext.contains("\n") || ext.contains("/") || ext.contains("\\")) {
            throw new Exception("ext contains forbidden characters");
        }
        return ext;
    }

    protected static void putIfNotNull(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    public static String getBaseURL() {
        return BASE_URL;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        if (config == null || config.getKey() == null || config.getKey().isBlank()) {
            throw new IllegalArgumentException("config with a non-blank key is required");
        }
        config.setBaseUri(resolveBaseUri(config.getBaseUri(), config.getKey()));
        this.config = config;
    }


}
