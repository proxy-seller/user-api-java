package org.proxyseller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload of {@code balance/autotopup/set}.
 *
 * <p><b>Partial update.</b> The server merges the request into the stored settings
 * and treats a missing field as "leave unchanged", so only the fields set here are
 * sent — {@link #toMap()} drops nulls instead of turning them into JSON nulls that
 * could reset a value. Changing just the threshold therefore means setting
 * {@link #threshold} alone.
 *
 * <p>Validation is entirely server side and is applied to the <i>merged</i> result,
 * so a locally valid partial request can still be rejected. Boundary values of a
 * rejection ({@code minAmount}, {@code minThreshold}) arrive in
 * {@code errors[0].customData}.
 *
 * <p>{@code dailyCountCap} and {@code monthlyAmountCap} were <b>removed from the contract</b>
 * on 2026-08-18 and are silently ignored by the server, so they are gone from here too — see
 * {@link Api#balanceAutoTopupSet(Map)}.
 */
public class AutoTopupOptions {

    /** Enable/disable auto top-up. Not set - the current state is kept. */
    public Boolean enabled;

    /** Charge when the balance drops below this value. Not set - kept. */
    public BigDecimal threshold;

    /** Amount of a single top-up; server minimum is $5 and not below the threshold. Not set - kept. */
    public BigDecimal amount;

    /**
     * Paddle subscription to charge, taken from {@code data.paymentMethod.id} of
     * {@code balance/autotopup/get}. While a single card is available it may be
     * omitted. Not set - the stored choice is kept.
     */
    public String subscriptionId;

    /**
     * @return only the fields that were actually set, so an omitted field never
     *         travels as null
     */
    public Map<Object, Object> toMap() {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        put(map, "enabled", enabled);
        put(map, "threshold", threshold);
        put(map, "amount", amount);
        put(map, "subscriptionId", subscriptionId);
        return map;
    }

    private static void put(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
