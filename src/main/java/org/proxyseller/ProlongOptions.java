package org.proxyseller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full payload accepted by {@code prolong/calc} and {@code prolong/make}.
 *
 * <p>Which selection field to fill depends on the proxy type in the path:
 * <ul>
 *   <li>{@code ipv4}, {@code isp} and {@code mobile} are renewed per proxy — set {@link #ids}
 *       or {@link #ips};</li>
 *   <li>{@code ipv6}, {@code mix} and {@code mix_isp} are renewed only as whole orders — set
 *       {@link #orderIds} instead of {@code ids}.</li>
 * </ul>
 * A field of the other kind is rejected with an error naming it, e.g.
 * {@code [ids] is not applicable for ipv6: prolong by [orderIds]} or
 * {@code [orderIds] is not applicable for ipv4: prolong by [ids]}.
 *
 * <p>Blank values are dropped from the selection collections, and a collection left empty is not
 * sent at all.
 *
 * <p>{@code periodId} and {@code paymentId} accept <b>an ObjectId or the matching code</b> — the
 * server falls back to a code lookup when the value is not a valid id and the paired {@code *Code}
 * field is empty. Setting a non-blank {@code *Code} field drops the paired {@code *Id} from the
 * payload — for these two pairs the server does prefer the code.
 */
public class ProlongOptions {
    /**
     * {@code ipv4}, {@code isp}, {@code mobile}: ids of the proxies to renew — the {@code id} field
     * of {@code proxy/list}. If both {@code ids} and {@link #ips} are set, the server uses
     * {@code ids} and ignores {@code ips}. Not accepted for ipv6/mix/mix_isp, which take
     * {@link #orderIds} instead.
     */
    public Collection<String> ids;
    /**
     * {@code ipv4}, {@code isp}, {@code mobile}: the addresses to renew instead of their ids,
     * exactly as {@code proxy/list} returns them — the plain {@code ip} ({@code 1.2.3.4}) for
     * ipv4/isp, {@code ip:port_http:port_socks} for mobile. Ignored by the server when
     * {@link #ids} is set as well.
     */
    public Collection<String> ips;
    /**
     * {@code ipv6}, {@code mix}, {@code mix_isp}: ids of the orders to renew, instead of
     * {@link #ids} — the {@code order_id} field of {@code proxy/list} or {@code order/list}.
     * Every active proxy of that type in those orders is renewed; for mix/mix_isp, the mix
     * packages of those orders. If any of the orders is not yours or has no active proxy of that
     * type, the whole request fails with {@code Incorrect orderIds} (code 29) and nothing is
     * renewed.
     */
    public Collection<String> orderIds;
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
        putSelection(map, "ids", ids);
        putSelection(map, "ips", ips);
        putSelection(map, "orderIds", orderIds);
        put(map, "coupon", coupon);
        put(map, "periodId", periodId);
        put(map, "periodCode", periodCode);
        put(map, "paymentId", paymentId);
        put(map, "paymentCode", paymentCode);
        // Для обеих пар продления сервер предпочитает code: заданный code парный id не смотрит.
        // Пустая строка при этом кодом НЕ считается: сервер обрезает пробелы и пустое значение
        // считает незаданным, а прежняя проверка на != null стирала валидный id.
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

    /**
     * Пустую выборку не шлём вовсе. Пустой ids рядом с ips выглядел бы для сервера как «ids
     * заданы» — а при обоих полях он берёт ids и адреса не смотрит. Пустые и null-значения
     * внутри коллекции выбрасываем по той же причине: список из одних пробелов — та же пустота.
     */
    private static void putSelection(Map<Object, Object> map, String key, Collection<String> values) {
        if (values == null) {
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value).trim();
            if (!text.isEmpty()) {
                cleaned.add(text);
            }
        }
        if (!cleaned.isEmpty()) {
            map.put(key, cleaned);
        }
    }
}
