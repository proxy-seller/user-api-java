package org.proxyseller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed request options shared by {@code order/calc} and {@code order/make}.
 *
 * <p>Every {@code *Id} field below accepts <b>an ObjectId or the matching code</b>: the server
 * falls back to a code lookup when the value is not a valid id and the paired {@code *Code} field
 * is empty. So the typed methods of {@link Api} already carry codes positionally, and this class
 * is for what they have no argument for — {@code protocol}, {@code uptime}, a per-request payment
 * system, {@code generateAuth}, or a mix selected through {@code countryId}.
 *
 * <p>Setting a {@code *Code} field drops the paired {@code *Id} from the payload
 * ({@link #toMap(boolean)}). The one field with no code form is {@link #rotationId} — minutes.
 */
public class OrderOptions {
    /** Country ObjectId, or the alpha-3 country code ({@code USA}); upper-cased server-side. */
    public String countryId;
    /** Alpha-3 country code. {@code reference/list} returns it as {@code country[].alpha3}. */
    public String countryCode;
    /** {@code ipv4}, {@code ipv6}, {@code isp}, {@code mobile}, {@code mix}, {@code resident}. */
    public String sectionCode;
    /** Period ObjectId, or the period code ({@code 1m}); lower-cased server-side. */
    public String periodId;
    /** Period code ({@code 1w}, {@code 1m}, {@code 3m}). Not returned by {@code reference/list}. */
    public String periodCode;
    public String coupon;
    /** Payment system ObjectId, or a payment code ({@code balance}) — resolved on order/prolong only. */
    public String paymentId;
    /** Payment code ({@code balance}). Not returned by {@code balance/payments/list}. */
    public String paymentCode;
    public Long quantity;
    public String authorization;
    /** Mandatory for ipv4/ipv6/isp and for an unresolved mix — otherwise {@code Incorrect goal} (14). */
    public String customTargetName;
    /** MIX package ObjectId, or the package tag (exact match). */
    public String mixId;
    /** MIX package tag. Exposed as {@code country[].tag} of the mix section in {@code reference/list}. */
    public String mixCode;
    public Boolean uptime;
    public String protocol;
    /** {@code shared} or {@code dedicated}. */
    public String mobileServiceType;
    /** Mobile operator ObjectId, or the operator tag (exact match). */
    public String operatorId;
    /** Mobile operator tag. Not returned by {@code reference/list} as a separate field. */
    public String operatorCode;
    /**
     * Rotation interval in <b>minutes</b> as a decimal string: {@code "5"}, {@code "10"},
     * {@code "0"} = By Link. Never an id and never a code — {@code "5m"} is rejected. The value
     * is {@code country[].operators.*[].rotations[].id} of {@code reference/list}.
     */
    public String rotationId;
    /**
     * Legacy twin of {@link #rotationId}: the server does not resolve it, it only checks that the
     * value is an integer and copies it into {@code rotationId}. Prefer {@link #rotationId}.
     */
    public String rotationCode;
    /** Resident tariff ObjectId, or the tariff code (exact match). */
    public String tarifId;
    /** Resident tariff code. Not returned by {@code reference/list}, which gives id and name. */
    public String tarifCode;
    /** {@code Y}/{@code N}, {@code order/make} only. */
    public String generateAuth;

    public Map<Object, Object> toMap(boolean makeOrder) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        put(map, "countryId", countryId);
        put(map, "countryCode", countryCode);
        put(map, "sectionCode", sectionCode);
        put(map, "periodId", periodId);
        put(map, "periodCode", periodCode);
        put(map, "coupon", coupon);
        put(map, "paymentId", paymentId);
        put(map, "paymentCode", paymentCode);
        put(map, "quantity", quantity);
        put(map, "authorization", authorization);
        put(map, "customTargetName", customTargetName);
        put(map, "mixId", mixId);
        put(map, "mixCode", mixCode);
        put(map, "uptime", uptime);
        put(map, "protocol", protocol);
        put(map, "mobileServiceType", mobileServiceType);
        put(map, "operatorId", operatorId);
        put(map, "operatorCode", operatorCode);
        put(map, "rotationId", rotationId);
        put(map, "rotationCode", rotationCode);
        put(map, "tarifId", tarifId);
        put(map, "tarifCode", tarifCode);
        if (makeOrder) {
            put(map, "generateAuth", generateAuth);
        }

        preferCode(map, "countryId", "countryCode", countryCode);
        preferCode(map, "periodId", "periodCode", periodCode);
        preferCode(map, "paymentId", "paymentCode", paymentCode);
        preferCode(map, "mixId", "mixCode", mixCode);
        preferCode(map, "operatorId", "operatorCode", operatorCode);
        preferCode(map, "rotationId", "rotationCode", rotationCode);
        preferCode(map, "tarifId", "tarifCode", tarifCode);
        return map;
    }

    private static void put(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static void preferCode(Map<Object, Object> map, String idKey, String codeKey, String code) {
        if (code != null) {
            map.remove(idKey);
            map.put(codeKey, code);
        }
    }
}
