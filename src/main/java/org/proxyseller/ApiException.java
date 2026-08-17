package org.proxyseller;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Client API failure. Business errors normally arrive with HTTP 200 and are
 * identified by {@link #getBusinessCode()}; transport/HTTP failures also retain
 * the HTTP status and original response body for diagnostics.
 *
 * <p>The envelope carries an {@code errors} <b>array</b>, not a single error.
 * Access failures (bad api key, IP not allowed, rate limit) always arrive as the
 * same fixed triple with HTTP 200 — see {@link #getErrors()}.
 */
public class ApiException extends Exception {
    private final Integer businessCode;
    private final Object customData;
    private final Integer httpStatus;
    private final String responseBody;
    private final Object responseData;
    private final List<Map<String, Object>> errors;

    public ApiException(String message, Integer businessCode, Object customData,
                        Integer httpStatus, String responseBody, Object responseData) {
        this(message, businessCode, customData, httpStatus, responseBody, responseData, null);
    }

    /**
     * @param message      message of the first error, or a synthesized description
     * @param businessCode code of the first error
     * @param customData   customData of the first error (auto top-up limits arrive here)
     * @param httpStatus   HTTP status of the response
     * @param responseBody raw response body
     * @param responseData {@code data} field of the envelope
     * @param errors       the complete {@code errors} array of the envelope
     */
    public ApiException(String message, Integer businessCode, Object customData,
                        Integer httpStatus, String responseBody, Object responseData,
                        List<Map<String, Object>> errors) {
        super(message);
        this.businessCode = businessCode;
        this.customData = customData;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.responseData = responseData;
        this.errors = errors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(errors);
    }

    public ApiException(String message, Integer httpStatus, String responseBody, Throwable cause) {
        super(message, cause);
        this.businessCode = null;
        this.customData = null;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.responseData = null;
        this.errors = Collections.emptyList();
    }

    public Integer getBusinessCode() {
        return businessCode;
    }

    /** Backward-friendly alias for {@link #getBusinessCode()}. */
    public Integer getCode() {
        return businessCode;
    }

    public Object getCustomData() {
        return customData;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Data returned with {@code status:error}. This is used, for example, by
     * insufficient-funds prolong responses where {@code errors} is empty.
     */
    public Object getResponseData() {
        return responseData;
    }

    /**
     * The complete {@code errors} array of the envelope, never null. Each entry
     * holds {@code message}, {@code code} and an optional {@code customData}.
     *
     * <p>Only the first entry is exposed through {@link #getMessage()},
     * {@link #getBusinessCode()} and {@link #getCustomData()}, and that is not
     * enough for access failures: the server answers them with a fixed triple
     * ("Error api key" / "IP not allowed &lt;ip&gt;" / "Request limit reached",
     * all with code 503) and only the whole array tells the three cases apart.
     *
     * @return the errors array, empty when the failure carried no envelope
     */
    public List<Map<String, Object>> getErrors() {
        return errors;
    }

    /**
     * True when the failure is the fixed access-error triple: a wrong api key,
     * an IP outside the allowlist, or an exceeded rate limit. The server sends
     * it with HTTP 200 (there is no HTTP 429 in Client API v2), so the status
     * code cannot be used for the check.
     *
     * @return whether the response is the access-error triple
     */
    public boolean isAccessError() {
        for (Map<String, Object> error : errors) {
            Object message = error == null ? null : error.get("message");
            if (message != null && "Error api key".equals(message.toString())) {
                return true;
            }
        }
        return false;
    }
}
