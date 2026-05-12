package org.proxyseller;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Proxy-seller api
 */
public class Api {
    private static final String BASE_URL = "https://proxy-seller.com/personal/api/v1/";
    private Config config;

    private int paymentId;
    private String generateAuth;

    /**
     * Key placed in <a href="https://proxy-seller.com/personal/api/">https://proxy-seller.com/personal/api/</a>
     *
     * @param config the configuration
     * @throws Exception if an error occurs
     */
    public Api(Config config) throws Exception {
        if (config.getKey().isEmpty()) {
            throw new Exception("Need key, placed in https://proxy-seller.com/personal/api/");
        }

        if (config.getBaseUri() == null || !config.getBaseUri().isBlank()) {
            config.setBaseUri(BASE_URL + config.getKey() + "/");
        }

        this.config = config;
    }

    public int getPaymentId() {
        return paymentId;
    }

    /**
     * Payment id=1(inner balance), id=43(subscribed card)
     * @param paymentId
     */
    public void setPaymentId(int paymentId) {
        this.paymentId = paymentId;
    }

    public String getGenerateAuth() {
        return generateAuth;
    }

    /**
     * Generate new auths Y/N, default N
     * @param yn
     */
    public void setGenerateAuth(String yn) {
        this.generateAuth = (Objects.equals(yn, "Y")) ? "Y" : "N";
    }

    protected byte[] requestDownload(String method, String uri) throws Exception {
        String fullUrl = config.getBaseUri() + uri;
        URL url = new URL(fullUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method.toUpperCase());
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[16384];
                while ((nRead = connection.getInputStream().read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                return buffer.toByteArray();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            throw new IOException("Request failed with response code: " + responseCode);
        }
        return null;
    }
    /**
     * Send request to the server.
     *
     * @param method  The HTTP method to use.
     * @param uri     The URI of the server.
     * @param options Additional options for the request.
     * @return Object The result of the request.
     * @throws Exception If an error occurs during the request.
     */
    protected Object request(String method, String uri, RequestOptions options) throws Exception {
        // Get query params
        String fullUrl = config.getBaseUri() + uri;
        if (options != null && options.getQuery() != null) {
            String queryString = options.getQuery().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("&"));
            fullUrl += "?" + queryString;
        }

        try {
            URL url = new URL(fullUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method.toUpperCase());

            // Body content
            if (options != null && !options.getJson().isEmpty()) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                Gson gson = new Gson();
                String jsonString = gson.toJson(options.getJson());
//                String jsonString = options.getJson().entrySet().stream()
//                        .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
//                        .collect(Collectors.joining(", ", "{", "}"));

                byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(jsonBytes);
                }
            }

            // Process response
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    String responseBody = response.toString();
                    try {
                        Map<String, Object> json = parseJson(responseBody);
                        if ("success".equals(json.get("status"))) {
                            return json.get("data");
                        } else if (json.containsKey("errors")) {
                            List<Map<String, Object>> errors = (List<Map<String, Object>>) json.get("errors");
                            throw new Exception(errors.get(0).get("message").toString());
                        }
                    } catch (JsonSyntaxException ignoring) {

                    }

                    return responseBody;
                }
            } else {
                throw new IOException("Request failed with response code: " + responseCode);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Json to Map
     * @param json String to decode
     * @return Map[String, Object]
     */
    public static Map<String, Object> parseJson(String json) {
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        Map<String, Object> map = gson.fromJson(json, type);
        return map;
    }

    /**
     * Send request to the server.
     *
     * @param method method The HTTP method to use.
     * @param uri    uri The URI of the server.
     * @return Object The result of the request.
     * @throws Exception If an error occurs during the request.
     */
    protected Object request(String method, String uri) throws Exception {
        return request(method, uri, null);
    }


    /**
     * Get auths
     * @return array Returns list auths
     */
    public List authList() throws Exception {
        return ((List) request("get", "auth/list"));
    }

    /**
     * Set auth active state
     * @param id id auth id
     * @param active active active state (Y/N)
     * @return current auth
     * @throws Exception
     */
    public Map authActive(Integer id, String active) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("active", active);
        options.setJson(map);

        return (Map) (request("post", "auth/active", options));
    }

    /**
     * Get balance statistic.
     *
     * @return float The balance statistic value.
     * @throws Exception Error
     */
    public Double balance() throws Exception {
        return (Double) (((Map<String, Object>) request("get", "balance/get")).get("summ"));
    }

    /**
     * Replenish the balance.
     *
     * @param summ      The amount to be replenished.
     * @param paymentId The ID of the payment.
     * @return String A link to the payment page.
     * Example: "<a href="https://proxy-seller.com/personal/pay/?ORDER_ID=123456789&amp;PAYMENT_ID=987654321&amp;HASH=343bd596fb97c04bfb76557710837d34">...</a>"
     * @throws Exception Error
     */
    public String balanceAdd(Double summ, Integer paymentId) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("summ", summ);
        map.put("paymentId", paymentId);
        options.setJson(map);

        return ((Map<String, Object>) request("post", "balance/add", options)).get("url").toString();
    }

    /**
     * Get a list of payment systems for balance replenishing.
     *
     * @return An array of payment system items.
     * Example items:
     * [
     * {
     * 'id' : '29',
     * 'name' : 'PayPal'
     * },
     * {
     * 'id' : '37',
     * 'name' : 'Visa / MasterCard'
     * }
     * ]
     * @throws Exception Error
     */
    public List balancePaymentsList() throws Exception {
        return ((List) ((Map<String, Object>) request("get", "balance/payments/list")).get("items"));
    }

    /**
     * Get necessary guides for creating an order.
     *
     * @param type The type of order (ipv4, ipv6, mobile, isp, mix, '').
     * @return The necessary guides for creating an order.
     * @throws Exception Error
     */
    public Map referenceList(String type) throws Exception {
        return ((Map) (request("get", "reference/list/" + type)));
    }

    /**
     * Get necessary guides for creating an order.
     *
     * @return The necessary guides for creating an order.
     * @throws Exception Error
     */
    public Map referenceList() throws Exception {
        return referenceList("");
    }

    /**
     * Calculate the order IPv4
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcIpv4(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderCalc(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Calculate the order ISP
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcIsp(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderCalc(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Calculate the order MIX
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcMix(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderCalc(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Calculate the order IPv6
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @param protocol         The protocol HTTPS or SOCKS5.
     * @return An array containing the order details
     * Example output:
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcIpv6(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName, String protocol) throws Exception {
        return orderCalc(prepareIpv6(countryId, periodId, quantity, authorization, coupon, customTargetName, protocol));
    }

    /**
     * Calculate the order Mobile
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param countryId     The ID of the country
     * @param periodId      The ID of the period
     * @param quantity      The quantity of the order
     * @param authorization IP whitelist (if need)
     * @param coupon        The coupon code
     * @param operatorId    The ID of the operator
     * @param rotationId    The ID of the rotation
     * @return An array containing the order details
     * Example output:
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcMobile(Integer countryId, String periodId, Integer quantity, String authorization, String coupon, Integer operatorId, Integer rotationId) throws Exception {
        return orderCalc(prepareMobile(countryId, periodId, quantity, authorization, coupon, operatorId, rotationId));
    }

    /**
     * Calculate the order Resident
     * Preliminary order calculation
     * An error in warning must be corrected before placing an order.
     *
     * @param tarifId   The ID of tarif
     * @param coupon    The coupon code
     * @return array Example
     * [
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02
     * ]
     * @throws Exception Error
     */
    public Map orderCalcResident(Integer tarifId, String coupon) throws Exception {
        return orderCalc(prepareResident(tarifId, coupon));
    }

    /**
     * Create an order IPv4
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeIpv4(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderMake(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Create an order ISP
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeIsp(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderMake(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Create an order MIX
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeMix(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) throws Exception {
        return orderMake(prepareIpv4(countryId, periodId, quantity, authorization, coupon, customTargetName));
    }

    /**
     * Create an order IPv6
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param countryId        The ID of the country
     * @param periodId         The ID of the period
     * @param quantity         The quantity of the order
     * @param authorization    IP whitelist (if need)
     * @param coupon           The coupon code
     * @param customTargetName The custom target name
     * @param protocol         The protocol HTTPS or SOCKS5.
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeIpv6(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName, String protocol) throws Exception {
        return orderMake(prepareIpv6(countryId, periodId, quantity, authorization, coupon, customTargetName, protocol));
    }

    /**
     * Create an order Mobile
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param countryId     The ID of the country
     * @param periodId      The ID of the period
     * @param quantity      The quantity of the order
     * @param authorization IP whitelist (if need)
     * @param coupon        The coupon code
     * @param operatorId    The ID of the operator
     * @param rotationId    The ID of the rotation
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeMobile(Integer countryId, String periodId, Integer quantity, String authorization, String coupon, Integer operatorId, Integer rotationId) throws Exception {
        return orderMake(prepareMobile(countryId, periodId, quantity, authorization, coupon, operatorId, rotationId));
    }

    /**
     * Create an order Resident
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /order/calc method. Practice there before calling the /order/make method.
     *
     * @param tarifId     The ID of the tarif
     * @param coupon        The coupon code
     * @return An array containing the order details
     * Example output:
     * [
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * ]
     * @throws Exception Error
     */
    public Map orderMakeResident(Integer tarifId, String coupon) throws Exception {
        return orderMake(prepareResident(tarifId, coupon));
    }

    protected Map prepareIpv4(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("paymentId", this.paymentId);
        map.put("generateAuth", this.generateAuth);
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("customTargetName", customTargetName);
        return map;
    }

    protected Map prepareIpv6(Integer countryId, String periodId, Integer quantity, String authorization, String coupon,  String customTargetName, String protocol) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("paymentId", this.paymentId);
        map.put("generateAuth", this.generateAuth);
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("customTargetName", customTargetName);
        map.put("protocol", protocol);
        return map;
    }

    protected Map prepareMobile(Integer countryId, String periodId, Integer quantity, String authorization, String coupon, Integer operatorId, Integer rotationId) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("paymentId", this.paymentId);
        map.put("generateAuth", this.generateAuth);
        map.put("countryId", countryId);
        map.put("periodId", periodId);
        map.put("quantity", quantity);
        map.put("authorization", authorization);
        map.put("coupon", coupon);
        map.put("operatorId", operatorId);
        map.put("rotationId", rotationId);
        return map;
    }
    protected Map prepareResident(Integer tarifId, String coupon) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("paymentId", this.paymentId);
        map.put("tarifId", tarifId);
        map.put("coupon", coupon);
        return map;
    }
    /**
     * Calculate the order.
     *
     * @param json A free format map to send to the endpoint.
     * @return The result of the order calculation.
     * @throws Exception Error
     */
    public Map orderCalc(Map json) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(json);
        return ((Map) (request("post", "order/calc", options)));
    }

    /**
     * Create an order.
     *
     * @param json A free format map to send to the endpoint.
     * @return The result of the order calculation.
     * @throws Exception Error
     */
    public Map orderMake(Map json) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(json);
        return ((Map) (request("post", "order/make", options)));
    }

    protected static Map prepareProlong(List ids, String periodId, String coupon) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("ids", ids);
        map.put("periodId", periodId);
        map.put("coupon", coupon);
        return map;
    }

    /**
     * Calculate the renewal.
     *
     * @param type     The type of the renewal (ipv4, ipv6, mobile, isp, mix).
     * @param ids      The list of IDs.
     * @param periodId The period ID.
     * @param coupon   The coupon code.
     * @return The result of the renewal calculation.
     * Example:
     * {
     * 'warning': 'Insufficient funds. Total $2. Not enough $33.10',
     * 'balance': 2,
     * 'total': 35.1,
     * 'quantity': 5,
     * 'currency': 'USD',
     * 'discount': 0.22,
     * 'price': 7.02,
     * 'items': [],
     * 'orders': 1
     * }
     * @throws Exception Error
     */
    public Map prolongCalc(String type, List ids, String periodId, String coupon) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(prepareProlong(ids, periodId, coupon));
        return ((Map) (request("post", "prolong/calc/" + type, options)));
    }

    /**
     * Create a renewal order.
     * Attention! Calling this method will deduct $ from your balance!
     * The parameters are identical to the /prolong/calc method. Practice there before calling the /prolong/make method.
     * @param type     The type of the renewal (ipv4, ipv6, mobile, isp, mix).
     * @param ids      The list of IDs.
     * @param periodId The period ID.
     * @param coupon   The coupon code.
     * @return The result of the renewal order creation.
     * Example:
     * {
     * 'orderId': 1000000,
     * 'total': 35.1,
     * 'balance': 10.19
     * }
     * @throws Exception Error
     */
    public Map prolongMake(String type, List ids, String periodId, String coupon) throws Exception {
        RequestOptions options = new RequestOptions();
        options.setJson(prepareProlong(ids, periodId, coupon));
        return ((Map) (request("post", "prolong/make/" + type, options)));
    }

    /**
     * Get the list of proxies.
     *
     * @param type The type of proxies (ipv4, ipv6, mobile, isp, mix, '').
     * @return The list of proxies.
     * Example:
     * [
     * {
     * 'id' : 9876543,
     * 'order_id' : 123456,
     * 'basket_id' : 9123456,
     * 'ip' : '127.0.0.2',
     * 'ip_only' : '127.0.0.2',
     * 'protocol' : 'HTTP',
     * 'port_socks' : 50101,
     * 'port_http' : 50100,
     * 'login' : 'login',
     * 'password' : 'password',
     * 'auth_ip' : '',
     * 'rotation' : '',
     * 'link_reboot' : '#',
     * 'country' : 'France',
     * 'country_alpha3' : 'FRA',
     * 'status' : 'Active',
     * 'status_type' : 'ACTIVE',
     * 'can_prolong' : 1,
     * 'date_start' : '26.06.2023',
     * 'date_end' : '26.07.2023',
     * 'comment' : '',
     * 'auto_renew' : 'Y',
     * 'auto_renew_period' : ''
     * }
     * ]
     * @throws Exception Error
     */
    public Map proxyList(String type) throws Exception {
        return ((Map) (request("get", "proxy/list/" + type)));
    }

    /**
     * Get the list of all proxy types.
     *
     * @return The list of proxies.
     * Example:
     * [
     * {
     * 'id' : 9876543,
     * 'order_id' : 123456,
     * 'basket_id' : 9123456,
     * 'ip' : '127.0.0.2',
     * 'ip_only' : '127.0.0.2',
     * 'protocol' : 'HTTP',
     * 'port_socks' : 50101,
     * 'port_http' : 50100,
     * 'login' : 'login',
     * 'password' : 'password',
     * 'auth_ip' : '',
     * 'rotation' : '',
     * 'link_reboot' : '#',
     * 'country' : 'France',
     * 'country_alpha3' : 'FRA',
     * 'status' : 'Active',
     * 'status_type' : 'ACTIVE',
     * 'can_prolong' : 1,
     * 'date_start' : '26.06.2023',
     * 'date_end' : '26.07.2023',
     * 'comment' : '',
     * 'auto_renew' : 'Y',
     * 'auto_renew_period' : ''
     * }
     * ]
     * @throws Exception Error
     */
    public Map proxyList() throws Exception {
        return proxyList("");
    }

    /**
     * Export proxies of a certain type in TXT or CSV format.
     *
     * @param type  The type of proxies (ipv4, ipv6, mobile, isp, mix).
     * @param ext   txt/csv
     * @param proto HTTPS/SOCKS
     * @param listId only for resident, if not set - will return ip from all sheets
     * @return String The exported proxies in the specified format.
     * Example:
     * login:password@127.0.0.2:50100
     * @throws Exception Error
     */
    public String proxyDownload(String type, String ext, String proto, Integer listId) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("ext", ext);
        map.put("proto", proto);
        map.put("listId", listId);
        options.setQuery(map);
        return request("get", "proxy/download/" + type, options).toString();
    }

    /**
     * Set a comment for proxies.
     *
     * @param ids     The list of IDs for the proxies.
     * @param comment The comment to set.
     * @return Integer The count of updated proxies.
     * @throws Exception Error
     */
    public Integer proxyCommentSet(List ids, String comment) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("ids", ids);
        map.put("comment", comment);
        options.setJson(map);
        return ((Double) (((Map<String, Object>) request("post", "proxy/comment/set", options)).get("updated"))).intValue();
    }

    /**
     * Check a single proxy.
     *
     * @param proxy The proxy to check. Available values: user:password@127.0.0.1:8080, user@127.0.0.1:8080, 127.0.0.1:8080.
     * @return The result of the proxy check.
     * Example result:
     * {
     * 'ip' : '127.0.0.1',
     * 'port' : 8080,
     * 'user' : 'user',
     * 'password' : 'password',
     * 'valid' : true,
     * 'protocol' : 'HTTP',
     * 'time' : 1234
     * }
     * @throws Exception Error
     */
    public Map proxyCheck(String proxy) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("proxy", proxy);
        options.setQuery(map);
        return (Map) request("get", "tools/proxy/check", options);
    }

    /**
     * Check the availability of the service.
     *
     * @return Integer The timestamp indicating the availability of the service.
     * @throws Exception Error
     */
    public Integer ping() throws Exception {
        return ((Double) ((Map<String, Object>) request("get", "system/ping")).get("pong")).intValue();
    }

    /**
     * Package Information
     * Remaining traffic, end date
     * @return array Example
     * [
     *     'is_active': true,
     *     'rotation': 60,
     *     'tarif_id': 2,
     *     'traffic_limit': 7516192768,
     *     'traffic_usage': 10,
     *     'expired_at': "d.m.Y H:i:s",
     *     'auto_renew': false
     * ]
     */
    public Map residentPackage() throws Exception {
        return ((Map) (request("get", "resident/package")));
    }

    /**
     * Database geo locations (zip ~300Kb, unzip ~3Mb)
     * @return binary
     */
    public byte[] residentGeo() throws Exception {
        return requestDownload("get", "resident/geo");
    }

    /**
     * List of existing ip list in a package
     * You can download the list via endpoint /proxy/download/resident?listId=123
     * @return map
     * @throws Exception
     */
    public List residentList() throws Exception {
        return ((List) (request("get", "resident/lists")));
    }

    /**
     * Create list in package
     * You can download the list via endpoint /proxy/download/resident?listId=123
     * @param title
     * @param whitelist
     * @param country
     * @param region
     * @param city
     * @param isp
     * @return
     * @throws Exception
     */
    public Map residentListAdd(String title, String whitelist, String country, String region, String city, String isp) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> geo = new LinkedHashMap<>();
        geo.put("country", country);
        geo.put("region", region);
        geo.put("city", city);
        geo.put("isp", isp);

        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("whitelist", whitelist);
        map.put("geo", geo);

        options.setJson(map);
        return (Map) (request("post", "resident/list", options));
    }

    /**
     * Rename list in user package
     * @param id The list ID
     * @param title title list
     * @return map
     * @throws Exception
     */
    public Map residentListRename(Integer id, String title) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        options.setJson(map);

        return (Map) (request("post", "resident/list/rename", options));
    }

    /**
     * Remove list from user package
     * @param id - The list ID
     * @return Updated list model
     */
    public Map residentListDelete(Integer id) throws Exception {
        RequestOptions options = new RequestOptions();
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        options.setQuery(map);

        return (Map) (request("delete", "resident/list/delete", options));
    }

    public static String getBaseURL() {
        return BASE_URL;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }


}
