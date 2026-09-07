package org.proxyseller;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full payload accepted by {@code prolong/calc} and {@code prolong/make}.
 *
 * <p>{@code periodId} and {@code paymentId} accept <b>an ObjectId or the matching code</b> — the
 * server falls back to a code lookup when the value is not a valid id and the paired {@code *Code}
 * field is empty. Setting a non-blank {@code *Code} field drops the paired {@code *Id} from the
 * payload — for these two pairs the server does prefer the code.
 */
public class ProlongOptions {
    /** IP address ids to renew (ObjectId strings). */
    public Collection<String> ids;
    /**
     * The addresses themselves instead of ids — exactly as {@code proxy/list} returns them:
     * {@code 1.2.3.4} for ipv4/isp/mix, {@code host:port} for ipv6,
     * {@code ip:port_http:port_socks} for mobile. The server resolves them into ids
     * ({@code ClientApiService.resolveProlongIpsToIds}). If both are set, the server uses
     * {@code ids}.
     *
     * <p>For ipv6 the {@code ip} field already contains the gateway and its port
     * ({@code 1.2.3.4:26000}) while {@code ip_only} holds the gateway alone — pass {@code ip}
     * as-is, same as every other type.
     */
    public Collection<String> ips;
    /** Order separator ids of a MIX order to renew (ObjectId strings). */
    public Collection<String> orderSeparatorIds;
    /** A single MIX order separator id (ObjectId string). */
    public String orderSeparatorId;
    public String coupon;
    /** Period ObjectId, or the period code ({@code 1m}); lower-cased server-side. */
    public String periodId;
    /** Period code ({@code 1w}, {@code 1m}, {@code 3m}). Not returned by {@code reference/list}. */
    public String periodCode;
    /** Payment system ObjectId, or a payment code ({@code balance}). */
    public String paymentId;
    /** Payment code ({@code balance}). Not returned by {@code balance/payments/list}. */
    public String paymentCode;

    public Map<Object, Object> toMap() {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        put(map, "ids", ids);
        put(map, "ips", ips);
        put(map, "orderSeparatorIds", orderSeparatorIds);
        put(map, "orderSeparatorId", orderSeparatorId);
        put(map, "coupon", coupon);
        put(map, "periodId", periodId);
        put(map, "periodCode", periodCode);
        put(map, "paymentId", paymentId);
        put(map, "paymentCode", paymentCode);
        // Обе пары прольонга — из тех, где старше code (normalizeProlongReferenceCodes: ветка
        // кода на парный id не смотрит). Пустая строка при этом кодом НЕ считается: сервер везде
        // проходит значение через trimToNull, а прежняя проверка на != null стирала валидный id.
        preferCode(map, "periodId", periodCode);
        preferCode(map, "paymentId", paymentCode);
        return map;
    }

    private static void preferCode(Map<Object, Object> map, String idKey, String code) {
        if (code != null && !code.trim().isEmpty()) {
            map.remove(idKey);
        }
    }

    private static void put(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
