package org.proxyseller;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed request options shared by {@code order/calc} and {@code order/make}. */
public class OrderOptions {
    public String countryId;
    public String countryCode;
    public String sectionCode;
    public String periodId;
    public String periodCode;
    public String coupon;
    public String paymentId;
    public String paymentCode;
    public Long quantity;
    public String authorization;
    public String customTargetName;
    public String mixId;
    public String mixCode;
    public Boolean uptime;
    public String protocol;
    public String mobileServiceType;
    public String operatorId;
    public String operatorCode;
    public String rotationId;
    public String rotationCode;
    public String tarifId;
    public String tarifCode;
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
