package org.proxyseller;

import java.util.LinkedHashMap;
import java.util.Map;

public class RequestOptions {
    private Map<Object, Object> json = new LinkedHashMap<Object, Object>();
    private Map<Object, Object> query = new LinkedHashMap<Object, Object>();

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
}
