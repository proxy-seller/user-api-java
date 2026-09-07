package org.proxyseller;

import java.util.LinkedHashMap;
import java.util.Map;

public class RequestOptions {
    private Map<Object, Object> json = new LinkedHashMap<Object, Object>();
    private Map<Object, Object> query = new LinkedHashMap<Object, Object>();
    private Map<Object, Object> headers = new LinkedHashMap<Object, Object>();

    public Map<Object, Object> getJson() {
        return json;
    }

    public void setJson(Map<Object, Object> json) {
        this.json = json;
    }

    public Map<Object, Object> getQuery() {
        return query;
    }

    public void setQuery(Map<Object, Object> query) {
        this.query = query;
    }

    /**
     * Дополнительные заголовки запроса. Раньше в запрос были жёстко зашиты только
     * Accept/Content-Type, и послать {@code X-Fingerprint} было нечем — а без него
     * {@code order/make} не создаёт резидентские и скраперные заказы вовсе.
     *
     * @return изменяемая карта заголовков, по умолчанию пустая
     */
    public Map<Object, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<Object, Object> headers) {
        this.headers = headers;
    }
}
