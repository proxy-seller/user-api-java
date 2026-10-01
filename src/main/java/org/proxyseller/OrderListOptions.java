package org.proxyseller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query filters accepted by {@code order/list}. Every field is optional, and every one of them
 * travels in the query string.
 *
 * <p>Query filters and response fields of {@code order/list} use snake_case names such as
 * {@code start_date}, {@code is_extend} — not the camelCase of {@code proxy/list} — and
 * {@link #toMap()} sends each field under that name. The server does not validate any of them
 * either — an unknown value is simply not applied as a filter instead of producing an HTTP 400
 * outside the envelope.
 *
 * <p>Unlike {@link Api#proxyList(String, String, String, String, String, Integer, Integer)}, there
 * is no flat overload: {@code order/list} has no mandatory argument, so a positional form would be
 * ten {@code null}s at every call site. This is the same reason {@link OrderOptions} exists.
 */
public class OrderListOptions {
    /**
     * Filter by order, sent as {@code order_id}. Takes either the numeric {@code id} of a row
     * (returns that row only) or the order's {@code order_id} ObjectId (returns the purchase and
     * all its prolongation rows). Exact match only; {@code 0} or an empty value means no filter.
     */
    public String orderId;
    /** Lower bound on the creation date, either ISO ({@code 2026-09-01}) or {@code dd.MM.yyyy}; both are accepted. */
    public String startDate;
    /** Upper bound on the creation date; a date without time covers the whole day. */
    public String endDate;
    /**
     * {@code PAYED} | {@code NOT_PAYED} | {@code RETURN} — the {@code status_type} of the
     * response, not the human-readable {@code status}, which moves with the translations.
     */
    public String status;
    /** {@code Y} | {@code N}: renewals only, or first purchases only. */
    public String isExtend;
    /** {@code Y} | {@code N}: filter by whether auto-renewal is on for the order. */
    public String autoOrder;
    /** Page number. */
    public Integer page;
    /**
     * Page size. Without it the whole list comes back as a single page — {@code metadata} is
     * still present, with {@code total_pages = 1} and {@code current_limit = 0}.
     */
    public Integer limit;
    /** {@code date_insert} | {@code summ} | {@code status}. */
    public String sortBy;
    /** {@code asc} | {@code desc}. */
    public String order;

    public Map<Object, Object> toMap() {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        put(map, "order_id", orderId);
        put(map, "start_date", startDate);
        put(map, "end_date", endDate);
        put(map, "status", status);
        put(map, "is_extend", isExtend);
        put(map, "auto_order", autoOrder);
        put(map, "page", page);
        put(map, "limit", limit);
        put(map, "sort_by", sortBy);
        put(map, "order", order);
        return map;
    }

    private static void put(Map<Object, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
