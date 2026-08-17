package org.proxyseller;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Full payload accepted by {@code prolong/calc} and {@code prolong/make}. */
public class ProlongOptions {
    public Collection<String> ids;
    public Collection<String> orderSeparatorIds;
    public String orderSeparatorId;
    public String coupon;
    public String periodId;
    public String periodCode;
    public String paymentId;
    public String paymentCode;

    public Map<Object, Object> toMap() {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        put(map, "ids", ids);
        put(map, "orderSeparatorIds", orderSeparatorIds);
        put(map, "orderSeparatorId", orderSeparatorId);
        put(map, "coupon", coupon);
        put(map, "periodId", periodId);
        put(map, "periodCode", periodCode);
        put(map, "paymentId", paymentId);
        put(map, "paymentCode", paymentCode);
        if (periodCode != null) {
            map.remove("periodId");
        }
        if (paymentCode != null) {
            map.remove("paymentId");
        }
        return map;
    }

    private static void put(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
