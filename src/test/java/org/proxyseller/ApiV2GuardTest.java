package org.proxyseller;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Локальные гарантии контракта Client API v2 — то, что можно проверить без сети.
 * Тесты закрывают именно те ловушки, из-за которых SDK раньше молча делал не то,
 * что просил клиент.
 */
class ApiV2GuardTest {

    private static Api api() throws Exception {
        return new Api(new Config("TEST_KEY"));
    }

    // --- baseUri: ключ живёт В ПУТИ -------------------------------------------------

    @Test
    void defaultBaseUriAppendsKey() throws Exception {
        assertEquals("https://proxy-seller.com/personal/api/v2/TEST_KEY/",
                api().getConfig().getBaseUri());
    }

    @Test
    void v2RootGetsTheKeyAppended() throws Exception {
        Api local = new Api(new Config("TEST_KEY", "http://localhost:7995/personal/api/v2/"));
        assertEquals("http://localhost:7995/personal/api/v2/TEST_KEY/", local.getConfig().getBaseUri());
    }

    @Test
    void templateAndCompletePerKeyUriBothWork() throws Exception {
        assertEquals("http://host/x/TEST_KEY/",
                new Api(new Config("TEST_KEY", "http://host/x/{apiKey}")).getConfig().getBaseUri());
        assertEquals("http://host/x/TEST_KEY/",
                new Api(new Config("TEST_KEY", "http://host/x/TEST_KEY/")).getConfig().getBaseUri());
    }

    @Test
    void customBaseUriWithoutTheKeyFailsLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Api(new Config("TEST_KEY", "http://host/some/other/root/")));
        assertTrue(e.getMessage().contains("does not contain the api key"), e.getMessage());
    }

    // --- proxy/replace: type это ПРИЧИНА замены, а не тип прокси --------------------

    @Test
    void replaceReasonIsValidatedAndNormalized() {
        assertEquals("NOT_WORK", Api.assertReplaceType("not_work", null));
        assertEquals("LOW_SPEED", Api.assertReplaceType(" LOW_SPEED ", null));
        assertEquals("CUSTOM", Api.assertReplaceType("custom", "slow in EU"));
    }

    @Test
    void proxyTypeIsRejectedAsReplaceReason() throws Exception {
        // Самая частая ошибка из-за перевёрнутого javadoc: сюда передавали "ipv4".
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api().proxyReplace(Arrays.asList("id1"), "ipv4", null));
        assertTrue(e.getMessage().contains("replacement reason"), e.getMessage());
    }

    @Test
    void customReasonRequiresComment() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> api().proxyReplace(Arrays.asList("id1"), "CUSTOM", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> api().proxyReplace(Arrays.asList("id1"), null, "whatever"));
    }

    // --- resident list id: числовой, Gson отдаёт Double -----------------------------

    @Test
    void residentListIdAcceptsEveryFormGsonCanProduce() {
        assertEquals(Long.valueOf(561L), Api.residentListId(561.0d));
        assertEquals(Long.valueOf(561L), Api.residentListId(561L));
        assertEquals(Long.valueOf(561L), Api.residentListId(561));
        assertEquals(Long.valueOf(561L), Api.residentListId("561"));
        assertEquals(Long.valueOf(561L), Api.residentListId("561.0"));
        assertEquals(Long.valueOf(561L), Api.residentListId(new BigDecimal("561.00")));
    }

    @Test
    void residentListIdRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Api.residentListId(null));
        assertThrows(IllegalArgumentException.class, () -> Api.residentListId("  "));
        assertThrows(IllegalArgumentException.class, () -> Api.residentListId("abc"));
        assertThrows(IllegalArgumentException.class, () -> Api.residentListId(561.5d));
    }

    // --- balance/add: paymentCode здесь НЕ резолвится -------------------------------

    @Test
    void balanceAddRefusesPaymentCodeWithAnExplanation() throws Exception {
        Api local = api();
        local.setPaymentCode("balance");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> local.balanceAdd(10.0));
        assertTrue(e.getMessage().contains("does not resolve paymentCode"), e.getMessage());
    }

    @Test
    void balanceAddRefusesAnEmptyPaymentId() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api().balanceAdd(10.0, "  "));
        assertTrue(e.getMessage().contains("paymentId is required"), e.getMessage());
    }

    // --- package_key работает только на subresident ---------------------------------

    @Test
    void packageKeyOnLiteralResidentRouteIsRejected() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api().proxyDownload("resident", "txt", "HTTPS", null, "PKG_KEY", null, null));
        assertTrue(e.getMessage().contains("subresident"), e.getMessage());
    }

    // --- ext: сервер отвечает голым 400 мимо конверта -------------------------------

    @Test
    void extIsValidatedLocally() {
        assertThrows(Exception.class, () -> Api.assertExt("a/b"));
        assertThrows(Exception.class, () -> Api.assertExt("a\nb"));
        assertThrows(Exception.class, () -> Api.assertExt("x".repeat(251)));
    }

    // --- autotopup: partial update, опущенное поле не уезжает как null --------------

    @Test
    void autoTopupOptionsSendOnlyWhatWasSet() {
        AutoTopupOptions options = new AutoTopupOptions();
        options.threshold = new BigDecimal("15");
        Map<Object, Object> map = options.toMap();
        assertEquals(1, map.size());
        assertTrue(map.containsKey("threshold"));
        assertFalse(map.containsKey("enabled"));
        assertFalse(map.containsKey("amount"));
    }

    @Test
    void autoTopupSetRefusesAnEmptyPayload() throws Exception {
        Api local = api();
        assertThrows(IllegalArgumentException.class, () -> local.balanceAutoTopupSet(new AutoTopupOptions()));
        LinkedHashMap<Object, Object> onlyNulls = new LinkedHashMap<>();
        onlyNulls.put("enabled", null);
        assertThrows(IllegalArgumentException.class, () -> local.balanceAutoTopupSet(onlyNulls));
    }

    // --- ApiException: клиенту нужен ВЕСЬ массив errors -----------------------------

    @Test
    void apiExceptionExposesTheWholeErrorsArray() {
        List<Map<String, Object>> errors = Arrays.asList(
                errorItem("Error api key", 503),
                errorItem("IP not allowed 10.0.0.1", 503),
                errorItem("Request limit reached", 503));
        ApiException e = new ApiException("Error api key", 503, null, 200, "{}", null, errors);

        assertEquals(3, e.getErrors().size());
        assertEquals("Request limit reached", e.getErrors().get(2).get("message"));
        assertTrue(e.isAccessError());
    }

    @Test
    void apiExceptionErrorsAreNeverNull() {
        ApiException e = new ApiException("boom", 500, "body", null);
        assertTrue(e.getErrors().isEmpty());
        assertFalse(e.isAccessError());
    }

    /**
     * Продление по самим адресам: их клиент видит в proxy/list, ObjectId — нет.
     * Сервер при наличии ids игнорирует ips, так что пустой ids рядом с ips отменил бы запрос.
     */
    @Test
    void addressesAreRoutedIntoIps() {
        List<List<String>> split = Api.splitProlongTargets(Arrays.asList("1.2.3.4", "5.6.7.8"));
        assertEquals(Arrays.asList("1.2.3.4", "5.6.7.8"), split.get(0));
        assertTrue(split.get(1).isEmpty());
    }

    @Test
    void objectIdsAreRoutedIntoIds() {
        List<List<String>> split = Api.splitProlongTargets(List.of("68b1f0c4e13a4c0f1a2b3c4d"));
        assertTrue(split.get(0).isEmpty());
        assertEquals(List.of("68b1f0c4e13a4c0f1a2b3c4d"), split.get(1));
    }

    @Test
    void mixedListIsRoutedByShape() {
        List<List<String>> split =
                Api.splitProlongTargets(Arrays.asList("1.2.3.4", "68b1f0c4e13a4c0f1a2b3c4d"));
        assertEquals(List.of("1.2.3.4"), split.get(0));
        assertEquals(List.of("68b1f0c4e13a4c0f1a2b3c4d"), split.get(1));
    }

    @Test
    void ipv6AndMobileFormatsAreAddresses() {
        List<List<String>> split =
                Api.splitProlongTargets(Arrays.asList("2001:db8::1:8080", "10.0.0.1:8000:9000"));
        assertEquals(Arrays.asList("2001:db8::1:8080", "10.0.0.1:8000:9000"), split.get(0));
        assertTrue(split.get(1).isEmpty());
    }

    @Test
    void blanksAndNullsAreDropped() {
        List<List<String>> split =
                Api.splitProlongTargets(Arrays.asList("1.2.3.4", "   ", "", null));
        assertEquals(List.of("1.2.3.4"), split.get(0));
        assertTrue(split.get(1).isEmpty());
    }

    @Test
    void prolongOptionsSendIpsWithoutAnEmptyIds() {
        ProlongOptions options = new ProlongOptions();
        options.ips = List.of("1.2.3.4");
        options.periodId = "1m";
        Map<Object, Object> payload = options.toMap();
        assertEquals(List.of("1.2.3.4"), payload.get("ips"));
        assertFalse(payload.containsKey("ids"), "ids must be absent: the server prefers it over ips");
    }

    private static Map<String, Object> errorItem(String message, int code) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("message", message);
        item.put("code", code);
        return item;
    }
}
