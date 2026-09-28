package org.proxyseller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    // --- продление: поле выборки зависит от ТИПА ------------------------------------

    /**
     * ipv4 / isp / mobile продлеваются по отдельным прокси: id прокси уходит в ipIds.
     * ipv6 / mix / mix_isp — только целым заказом: всё, что не адрес, — это order_id, и id прокси
     * в ipIds сервер для них отбивает ({@code [ipIds] is not applicable for ipv6 ...}).
     */
    @Test
    void proxyIdOfPerProxyTypeIsRoutedIntoIpIds() {
        for (String type : Arrays.asList("ipv4", "isp", "mobile")) {
            ProlongOptions options = routed(type, "68b1f0c4e13a4c0f1a2b3c4d");
            assertEquals(List.of("68b1f0c4e13a4c0f1a2b3c4d"), options.ipIds, type);
            assertNull(options.orderIds, type);
            assertNull(options.ips, type);
        }
    }

    @Test
    void idOfWholeOrderTypeIsRoutedIntoOrderIds() {
        for (String type : Arrays.asList("ipv6", "mix", "mix_isp")) {
            ProlongOptions options = routed(type, "6a248de4717805635cf6057d");
            assertEquals(List.of("6a248de4717805635cf6057d"), options.orderIds, type);
            assertNull(options.ipIds, type);
            assertNull(options.ips, type);
        }
    }

    /** Тип сравнивается так же, как на сервере: регистр, края, "-" и пробел вместо "_". */
    @Test
    void typeIsNormalizedBeforeRouting() {
        for (String type : Arrays.asList("IPv6", " MIX ", "mix-isp", "MIX ISP", "Mix_Isp")) {
            assertEquals(List.of("6a248de4717805635cf6057d"),
                    routed(type, "6a248de4717805635cf6057d").orderIds, type);
        }
        assertEquals(List.of("68b1f0c4e13a4c0f1a2b3c4d"),
                routed(" IPv4 ", "68b1f0c4e13a4c0f1a2b3c4d").ipIds);
        assertEquals("mix_isp", Api.normalizeProxyType(" Mix-ISP "));
        assertEquals("", Api.normalizeProxyType(null));
    }

    /** Адрес (точка или двоеточие) уходит в ips при любом типе — отбивать его или нет, решает сервер. */
    @Test
    void addressesAreRoutedIntoIpsWhateverTheType() {
        for (String type : Arrays.asList("ipv4", "isp", "mobile", "ipv6", "mix", "mix_isp")) {
            ProlongOptions options = routed(type, "1.2.3.4", "10.0.0.1:8000:9000");
            assertEquals(Arrays.asList("1.2.3.4", "10.0.0.1:8000:9000"), options.ips, type);
            assertNull(options.ipIds, type);
            assertNull(options.orderIds, type);
        }
    }

    /**
     * ipv4 / isp / mobile: при ipIds сервер адреса из ips не смотрит вовсе, так что смешанный
     * список молча продлил бы только часть оплаченного. Такой список отбивается до запроса.
     */
    @Test
    void mixedIdsAndAddressesAreRejectedForPerProxyTypes() {
        for (String type : Arrays.asList("ipv4", "isp", "mobile", " ISP ")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> routed(type, "1.2.3.4", "68b1f0c4e13a4c0f1a2b3c4d"), type);
            assertTrue(e.getMessage().startsWith(
                    "Mixing proxy ids and addresses in one call is not supported: pass either ids or addresses"),
                    e.getMessage());
        }
    }

    /** Все пять списочных перегрузок отбивают смесь ДО запроса — в том числе платный prolongMake. */
    @Test
    void mixedListNeverLeavesThePublicOverloads() throws Exception {
        Api local = api();
        local.setPaymentCode("balance");
        List<String> mixed = Arrays.asList("10.0.0.1:8000:9000", "68b1f0c4e13a4c0f1a2b3c4d");
        List<Executable> calls = Arrays.asList(
                () -> local.prolongCalc("mobile", mixed, "1m", null),
                () -> local.prolongMake("mobile", mixed, "1m", null),
                () -> local.autoProlongCalc("mobile", mixed, "1m"),
                () -> local.autoProlongEnable("mobile", mixed, "1m"),
                () -> local.autoProlongDisable("mobile", mixed));
        for (Executable call : calls) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
            assertTrue(e.getMessage().contains("Mixing proxy ids and addresses"), e.getMessage());
        }
    }

    /** ipv6 / mix / mix_isp: смесь раскладывается как есть, адресную часть отбивает сам сервер. */
    @Test
    void mixedListOfWholeOrderTypeKeepsRouting() {
        for (String type : Arrays.asList("ipv6", "mix", "mix_isp")) {
            ProlongOptions options = routed(type, "1.2.3.4", "6a248de4717805635cf6057d");
            assertEquals(List.of("1.2.3.4"), options.ips, type);
            assertEquals(List.of("6a248de4717805635cf6057d"), options.orderIds, type);
            assertNull(options.ipIds, type);
        }
    }

    /**
     * Резидентка продлевается пакетом целиком: любая выборка отбивается до запроса и НЕ
     * выбрасывается молча — иначе disable «для пары адресов» выключил бы автопродление всего
     * пакета. Отказ идёт раньше проверки платёжки: платёжка здесь не задана намеренно.
     */
    @Test
    void residentAutoProlongRejectsAnySelection() throws Exception {
        Api local = api();
        List<Executable> calls = new ArrayList<>();
        calls.add(() -> local.autoProlongCalc("resident", List.of("1.2.3.4"), null));
        calls.add(() -> local.autoProlongEnable("resident", List.of("68b1f0c4e13a4c0f1a2b3c4d"), null));
        calls.add(() -> local.autoProlongDisable("Resident",
                Arrays.asList("1.2.3.4", "68b1f0c4e13a4c0f1a2b3c4d")));

        AutoProlongOptions byIpIds = new AutoProlongOptions();
        byIpIds.ipIds = List.of("68b1f0c4e13a4c0f1a2b3c4d");
        AutoProlongOptions byIps = new AutoProlongOptions();
        byIps.ips = List.of("1.2.3.4");
        AutoProlongOptions byOrders = new AutoProlongOptions();
        byOrders.orderIds = List.of("6a248de4717805635cf6057d");
        for (AutoProlongOptions options : Arrays.asList(byIpIds, byIps, byOrders)) {
            calls.add(() -> local.autoProlongCalc("resident", options));
            calls.add(() -> local.autoProlongEnable("resident", options));
            calls.add(() -> local.autoProlongDisable("resident", options));
        }

        for (Executable call : calls) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
            assertTrue(e.getMessage().startsWith(
                    "resident auto-prolong applies to the whole package: do not pass proxy or order ids"),
                    e.getMessage());
        }
    }

    /** Пакетное тело резидентки проверку проходит; правило не задевает остальные типы. */
    @Test
    void residentWithoutSelectionPassesTheCheck() {
        AutoProlongOptions packageShaped = new AutoProlongOptions();
        packageShaped.paymentCode = "balance";
        packageShaped.tarifId = "1-gb";
        Api.assertNoResidentSelection("resident", packageShaped.toMap());

        AutoProlongOptions blanks = new AutoProlongOptions();
        blanks.ipIds = Arrays.asList(" ", null);
        Api.assertNoResidentSelection("resident", blanks.toMap());

        Api.assertNoResidentSelection("ipv4", routed("ipv4", "1.2.3.4").toMap());
        Api.assertNoResidentSelection("mix", routed("mix", "6a248de4717805635cf6057d").toMap());
    }

    /**
     * Удалённых полей выборки нет ни в ProlongOptions, ни в AutoProlongOptions, а сырого Map-пути
     * у prolong/* и autoprolong/* нет вовсе — старое имя поля не доедет до сервера ни одним путём.
     */
    @Test
    void removedSelectionFieldsDoNotExist() {
        for (Class<?> options : Arrays.asList(ProlongOptions.class, AutoProlongOptions.class)) {
            for (String removed : Arrays.asList("ids", "orderSeparatorIds", "orderSeparatorId")) {
                assertThrows(NoSuchFieldException.class, () -> options.getField(removed),
                        options.getSimpleName() + "." + removed);
            }
        }
    }

    /**
     * Пустые значения выбрасываются, пустой ключ не уходит вовсе: при заданном ipIds сервер
     * адреса из ips не смотрит, так что пустой ipIds рядом с ips сорвал бы продление.
     * Резидентка без выборки остаётся без выборки.
     */
    @Test
    void blanksAreDroppedAndNoEmptyKeyIsSent() {
        Map<Object, Object> payload = routed("ipv4", "1.2.3.4", "   ", "", null).toMap();
        assertEquals(List.of("1.2.3.4"), payload.get("ips"));
        assertFalse(payload.containsKey("ipIds"));
        assertFalse(payload.containsKey("orderIds"));

        for (String type : Arrays.asList("ipv4", "mobile", "ipv6", "mix_isp", "resident")) {
            Map<Object, Object> nothing = routed(type, " ", null).toMap();
            assertTrue(nothing.isEmpty(), type + ": " + nothing);
        }
        AutoProlongOptions resident = new AutoProlongOptions();
        Api.routeProlongTargets("resident", resident, null);
        assertTrue(resident.toMap().isEmpty(), "resident takes no selection at all");
    }

    @Test
    void prolongOptionsNeverSendRemovedFields() {
        ProlongOptions options = new ProlongOptions();
        options.ipIds = List.of("68b1f0c4e13a4c0f1a2b3c4d");
        options.ips = List.of("1.2.3.4");
        options.orderIds = List.of("6a248de4717805635cf6057d");
        options.coupon = "SALE10";
        options.periodId = "1m";
        options.paymentCode = "balance";

        AutoProlongOptions auto = new AutoProlongOptions();
        auto.orderIds = List.of("6a248de4717805635cf6057d");
        auto.periodId = "1m";
        auto.paymentId = "balance";
        auto.subscriptionId = "sub_01hxyz";

        for (Map<Object, Object> payload : Arrays.asList(options.toMap(), auto.toMap())) {
            for (String removed : Arrays.asList("ids", "orderSeparatorIds", "orderSeparatorId")) {
                assertFalse(payload.containsKey(removed), removed + " is no longer read by the server");
            }
        }
        Map<Object, Object> payload = options.toMap();
        assertEquals(List.of("68b1f0c4e13a4c0f1a2b3c4d"), payload.get("ipIds"));
        assertEquals(List.of("1.2.3.4"), payload.get("ips"));
        assertEquals(List.of("6a248de4717805635cf6057d"), payload.get("orderIds"));
        assertEquals(List.of("6a248de4717805635cf6057d"), auto.toMap().get("orderIds"));
    }

    @Test
    void prolongOptionsDoNotSendEmptyCollections() {
        ProlongOptions options = new ProlongOptions();
        options.ipIds = new ArrayList<>();
        options.ips = List.of("1.2.3.4");
        options.orderIds = Arrays.asList(" ", null);
        options.periodId = "1m";
        Map<Object, Object> payload = options.toMap();
        assertEquals(List.of("1.2.3.4"), payload.get("ips"));
        assertFalse(payload.containsKey("ipIds"), "an empty ipIds next to ips would win over the addresses");
        assertFalse(payload.containsKey("orderIds"));
        assertEquals("1m", payload.get("periodId"));
    }

    // --- prolong/make: успех — это продлённые заказы ---------------------------------

    /** Одним запросом продлевается несколько заказов: orderIds — все, orderId — первый из них. */
    @Test
    void prolongMadeIsRecognisedByOrderIds() throws Exception {
        Map<String, Object> made = new LinkedHashMap<>();
        made.put("orderIds", Arrays.asList("6a248de4717805635cf6057d", "6a248de4717805635cf6058a"));
        made.put("total", 25.0);
        made.put("listBaseOrderNumbers", List.of("NS_1790059585687-no"));
        assertSame(made, Api.assertProlongMade(made));

        Map<String, Object> onlyOrderId = new LinkedHashMap<>();
        onlyOrderId.put("orderId", "6a248de4717805635cf6057d");
        assertSame(onlyOrderId, Api.assertProlongMade(onlyOrderId));
    }

    /** Расчёт с warning вместо продления — не успех, даже если конверт отдал его как data. */
    @Test
    void prolongWithoutOrdersIsAnError() {
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("warning", "Insufficient funds. Total $25.00. Not enough $20.00");
        calc.put("total", 25.0);
        calc.put("orderIds", List.of());
        ApiException e = assertThrows(ApiException.class, () -> Api.assertProlongMade(calc));
        assertEquals("Insufficient funds. Total $25.00. Not enough $20.00", e.getMessage());
        assertSame(calc, e.getResponseData());

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("orderIds", Arrays.asList("", null));
        blank.put("orderId", " ");
        assertThrows(ApiException.class, () -> Api.assertProlongMade(blank));
    }

    // --- X-Fingerprint: необязателен, уходит только заданный ------------------------

    /**
     * Заказ с API-ключом сервер без отпечатка не отклоняет ни в одной секции — значит, и SDK
     * не должен ни падать, ни слать пустой заголовок.
     */
    @Test
    void residentAndScraperOrdersDoNotRequireAFingerprint() throws Exception {
        CapturingApi local = new CapturingApi(new Config("TEST_KEY"));
        local.setPaymentCode("balance");

        local.orderMakeResident("1-gb", null);
        assertEquals("order/make", local.lastUri);
        assertFalse(local.lastOptions.getHeaders().containsKey("X-Fingerprint"));

        local.orderMakeScraper("scraper-tariff", null);
        assertFalse(local.lastOptions.getHeaders().containsKey("X-Fingerprint"));

        LinkedHashMap<Object, Object> raw = new LinkedHashMap<>();
        raw.put("sectionCode", "resident");
        raw.put("tarifId", "1-gb");
        local.orderMake(raw, "   ");
        assertFalse(local.lastOptions.getHeaders().containsKey("X-Fingerprint"),
                "a blank per-call value is the same as none");
    }

    @Test
    void fingerprintIsSentWhenProvided() throws Exception {
        CapturingApi fromConfig = new CapturingApi(new Config("TEST_KEY", null, "from-config"));
        fromConfig.orderMakeResident("1-gb", null);
        assertEquals("from-config", fromConfig.lastOptions.getHeaders().get("X-Fingerprint"));

        fromConfig.setFingerprint(" installation-1 ");
        fromConfig.orderMakeScraper("scraper-tariff", null);
        assertEquals("installation-1", fromConfig.lastOptions.getHeaders().get("X-Fingerprint"));

        fromConfig.orderMakeResident("1-gb", null, "per-call");
        assertEquals("per-call", fromConfig.lastOptions.getHeaders().get("X-Fingerprint"));

        assertThrows(IllegalArgumentException.class,
                () -> fromConfig.orderMakeResident("1-gb", null, "bad\r\nX-Injected: 1"));
    }

    /** Резидентские методы автопродления шлют тело пакета без единого поля выборки. */
    @Test
    void residentAutoProlongMethodsSendNoSelection() throws Exception {
        CapturingApi local = new CapturingApi(new Config("TEST_KEY"));
        local.setPaymentCode("balance");

        local.autoProlongDisableResident();
        assertEquals("autoprolong/disable/resident", local.lastUri);
        assertEquals(Map.of("paymentCode", "balance"), local.lastOptions.getJson());

        local.autoProlongEnableResident("1-gb");
        assertEquals("autoprolong/enable/resident", local.lastUri);
        assertEquals(Map.of("paymentCode", "balance", "tarifId", "1-gb"), local.lastOptions.getJson());
    }

    // --- order/list: фильтры в snake_case, а не в camelCase proxy/list --------------

    /**
     * Фильтры order/list называются в snake_case ({@code start_date}, {@code is_extend} …),
     * а не в camelCase {@code proxy/list}. camelCase-имя сервер не распознаёт, и ошибка прошла
     * бы молча — запрос бы ушёл, фильтр бы не применился.
     */
    @Test
    void orderListOptionsUseSnakeCaseFilterNames() {
        OrderListOptions options = new OrderListOptions();
        options.orderId = "68b1f0c4e13a4c0f1a2b3c11";
        options.startDate = "01.06.2023";
        options.endDate = "30.06.2023";
        options.status = "PAYED";
        options.isExtend = "Y";
        options.autoOrder = "N";
        options.page = 1;
        options.limit = 20;
        options.sortBy = "date_insert";
        options.order = "desc";

        Map<Object, Object> query = options.toMap();
        assertEquals(10, query.size());
        assertEquals("68b1f0c4e13a4c0f1a2b3c11", query.get("order_id"));
        assertEquals("01.06.2023", query.get("start_date"));
        assertEquals("30.06.2023", query.get("end_date"));
        assertEquals("PAYED", query.get("status"));
        assertEquals("Y", query.get("is_extend"));
        assertEquals("N", query.get("auto_order"));
        assertEquals(1, query.get("page"));
        assertEquals(20, query.get("limit"));
        assertEquals("date_insert", query.get("sort_by"));
        assertEquals("desc", query.get("order"));
        assertFalse(query.containsKey("orderId"), "camelCase would silently disable the filter");
    }

    /** Все фильтры опциональны: незаданное поле не должно уезжать как пустой параметр. */
    @Test
    void orderListOptionsSendOnlyWhatWasSet() {
        OrderListOptions options = new OrderListOptions();
        options.status = "NOT_PAYED";
        Map<Object, Object> query = options.toMap();
        assertEquals(1, query.size());
        assertEquals("NOT_PAYED", query.get("status"));
        assertTrue(new OrderListOptions().toMap().isEmpty());
    }

    /** Api без сети: запоминает, что ушло бы на сервер, и отвечает пустыми данными. */
    private static final class CapturingApi extends Api {
        String lastUri;
        RequestOptions lastOptions;

        CapturingApi(Config config) throws Exception {
            super(config);
        }

        @Override
        protected Object request(String method, String uri, RequestOptions options) {
            lastUri = uri;
            lastOptions = options;
            return new LinkedHashMap<String, Object>();
        }
    }

    private static ProlongOptions routed(String type, String... values) {
        ProlongOptions options = new ProlongOptions();
        Api.routeProlongTargets(type, options, Arrays.asList(values));
        return options;
    }

    private static Map<String, Object> errorItem(String message, int code) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("message", message);
        item.put("code", code);
        return item;
    }
}
