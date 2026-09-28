# proxy-seller java api

Client API **v2**.

Install from [maven-central](https://mvnrepository.com/artifact/com.proxy-seller/proxy-seller-user-api/)
```gradle
implementation 'com.proxy-seller:proxy-seller-user-api:2.0'
```

Prebuilt jars for v2 live in
[dist/](https://github.com/proxy-seller/user-api-java/tree/main/dist):
`proxy-seller-user-api-2.0-standalone.jar` (fat jar, Gson included), plus the
sources and javadoc jars. They are produced by `./gradlew build_standalone`.

> ⚠️ **The jars in `dist/` are behind the sources.** They predate `ProlongOptions.ips` and
> `ProlongOptions.orderIds`, `X-Fingerprint`, `orderList()` and the `autoprolong/*` methods. Their
> renewal methods send every value as `ids`: that still renews ipv4, isp and mobile by proxy id,
> but ipv6, mix and mix_isp are renewed by `orderIds`, which those jars cannot send. Until they
> are regenerated (`./gradlew build_standalone`), build from source — the examples in this README
> assume the current sources.

Requires **JDK 17+**. **Android is not supported** — see [Platform support](#platform-support).

## Quick start
Get API key [here](https://proxy-seller.com/personal/api/)
```java
import org.proxyseller.Api;
import org.proxyseller.Config;

public class Proxy {

    public static void main(String[] args) throws Exception {
        Api api = new Api(new Config("YOUR_API_KEY"));
        System.out.println(api.balance());
    }
}
```

Nothing else is required — the client talks to `https://proxy-seller.com/personal/api/v2/` by
default. The api key is a **path segment** in v2
(`https://proxy-seller.com/personal/api/v2/{apiKey}/...`), not a header.

Requests are **paced by default**, so a loop of calls stays under the API's limits on its own —
see [Rate limits and the request queue](#rate-limits-and-the-request-queue).

### Paying for orders

Every order and renewal needs a payment system, and `order/make` and `prolong/*` accept exactly
two: the internal balance and a saved card. A one-off card checkout needs a browser redirect that
a programmatic client cannot complete. Set it once, by code:

```java
api.setPaymentCode("balance");                  // the internal balance
// api.setPaymentCode("paddle_subscription");   // the saved card — needs an active card subscription
```

Do not take it from `balancePaymentsList()`. That list holds the systems for **topping up** the
balance (`balanceAdd`, see [Balance](#balance)), and the internal balance itself is never in it.
An entry from it is refused by `order/make` with
`Set paymentId = <id>(inner balance) OR paymentId = <id>(subscribed card)`.

### Optional `X-Fingerprint`

`order/make` can carry an `X-Fingerprint` header: a stable, opaque identifier of your
installation, used for anti-fraud checks and affiliate attribution when present. It is
**optional** — no section, residential and scraper included, refuses an order without it. The SDK
sends the header when you give it a value and leaves it out otherwise:

```java
Api api = new Api(new Config("YOUR_API_KEY", null, "your-installation-id"));
// or later:
api.setFingerprint("your-installation-id");
// or for a single call:
api.orderMakeResident("tarif-code", null, "your-installation-id");
```

Any opaque string is accepted — the server does not validate its shape — but keep it a
**stable identifier of your installation**. The SDK deliberately does not generate one for you: a
value randomized per process would defeat the anti-fraud and affiliate attribution the header
exists for.

<details>
<summary>Pointing the client at another host, and timeouts</summary>

Pass the v2 API root. The key is appended and URL-encoded automatically; a complete per-key URL
or a URL with `{apiKey}` is also accepted:

```java
Config config = new Config("YOUR_API_KEY", "http://localhost:7995/personal/api/v2/");
config.setConnectTimeoutMillis(10_000);
config.setReadTimeoutMillis(30_000);
Api api = new Api(config);
```

A custom `baseUri` that is neither the v2 root nor contains the key is rejected
with an `IllegalArgumentException` at construction time, instead of silently
sending every request without a key.

</details>

## Identifiers are strings

Every id in v2 is a MongoDB **ObjectId string**: `countryId`, `periodId`,
`paymentId`, `operatorId`, `mixId`, `tarifId`, `orderId`, auth ids, IP address
ids. Do not parse them into `int`/`long` — they are not numbers, and the numeric
ids of v1 do not resolve at all.

Two fields are **not** ObjectIds:

* `rotationId` — the mobile rotation interval in **minutes** (`0` = By Link).
  See [Mobile rotation](#mobile-rotation-is-a-number-of-minutes).
* resident list ids — they stayed numeric. See
  [The one exception: resident list ids](#the-one-exception-resident-list-ids).

### An id argument also takes a code

Every reference `*Id` field of `order/*` and `prolong/*` has a code fallback: if
the value is not a valid id **and** the matching `*Code` field is empty, the
server resolves it as a code. That covers `countryId`, `periodId`, `paymentId`,
`operatorId`, `mixId` and `tarifId`.

So a code goes straight into the positional argument — no `OrderOptions` and no
chain of nulls is needed just to pass one:

```java
// countryId="USA" and periodId="1m" are resolved as codes
api.orderCalcIpv4("USA", "1m", 5L, null, null, "Research");

api.orderCalcMobile(
        "USA",          // countryId: ObjectId or alpha-3 country code
        "1m",           // periodId: ObjectId or period code
        1L,             // quantity
        null,           // authorization — optional IP whitelist
        null,           // coupon — optional
        "OPERATOR_ID",  // operatorId: ObjectId from the reference, or the operator tag
        "5",            // rotationId: MINUTES, "0" = By Link. Never a code — "5m" is rejected
        "dedicated");   // shared or dedicated
```

The two `null`s there are the genuinely optional `authorization` and `coupon`,
not placeholders for something that had to move into an options object.

Matching is not fully case-insensitive: a country code is upper-cased (`usa`
works), a period code is lower-cased (`1M` works), while an operator tag, a mix
tag, a tariff code and a payment code must match exactly.

### Mobile rotation is a number of minutes

`rotationId` is the one reference field with no code form. It is the rotation
interval in **minutes**, sent as a decimal string: `"5"`, `"10"`, `"0"` (`0` is
By Link). Anything else — `"5m"`, `"ROTATION_ID"`, an ObjectId — is not a value
the server can use.

`rotationCode` exists in the payload but is never resolved: the server only
copies it into `rotationId` and rejects it when it is not an integer. Set
`rotationId` and ignore `rotationCode`.

### What you can pass, and where to get it

Every field in `referenceList()` is called `id`, and its value is a readable
code — not an ObjectId. Read `id`, put it in the matching `*Id` request field.
That is the whole rule:

| field | pass this | read it from |
|---|---|---|
| `countryId` | alpha-3 country code, e.g. `USA` (upper-cased server-side, so `usa` works) | `country[].id` |
| `periodId` | period code, e.g. `1m` (lower-cased server-side) | `period[].id` |
| `operatorId` | mobile operator code — exact match, case-sensitive | `country[].operators.dedicated[]`/`.shared[]` → `id` |
| `rotationId` | minutes as a string (`0` = By Link) — the one `id` that is a number, not a code | `operators.*[].rotations[].id` **is** the minutes, `name` is `"5 minutes"` / `"By Link"` |
| `mixId` | mix package code — exact match, or its ObjectId | `reference/list/mix` → `quantities[].id`, e.g. `europe-2-mix_IPv4`. First argument of `orderCalcMix`/`orderMakeMix` |
| `tarifId` | resident tariff code — exact match, e.g. `1-gb` | `resident.tarifs[]` → `id` |
| `paymentId` | orders and renewals: the code `balance` or `paddle_subscription`; `balanceAdd`: a top-up system's ObjectId | orders: fixed codes, see [Paying for orders](#paying-for-orders); top-ups: `balancePaymentsList()` → `id` |

ObjectIds are still accepted everywhere if you happen to have them; the
reference simply no longer publishes them.

Code resolution lives in `order/calc`, `order/make`, `prolong/calc` and
`prolong/make`. Every other endpoint — `proxy/*`, `auth/*`, `balance/add`,
`resident/*` — works with ids; the exception is renewal of ipv4, isp and mobile,
which also takes the proxy addresses (see [Renewing proxies](#renewing-proxies)).

### The one exception: resident list ids

Resident list ids stayed **numeric** (`Long`), and Gson decodes untyped JSON
numbers as `Double`. So `residentList().get(0).get("id")` is a `Double` like
`561.0` — casting it to `Long` throws `ClassCastException`, and `"" + id`
produces the string `"561.0"`, which never matches on the server.

Pass the raw value into the `Object` overloads and the SDK normalizes it:

```java
for (Object item : api.residentList()) {
    Object id = ((java.util.Map) item).get("id");   // Double 561.0
    api.residentListRename(id, "US Washington");    // sends 561
    api.residentListRotation(id, 60);
    api.residentListDelete(id);
}
```

The same overloads exist for subpackage lists:
`residentSubUserListRename(packageKey, id, title)`,
`residentSubUserListRotation(packageKey, id, rotation)` and
`residentSubUserListDelete(packageKey, id)`. The typed `Long`/`Integer`/`String`
signatures still work unchanged.

## Client API v2 options

The typed methods take everything an order needs positionally, ids and codes
alike. Every `null` below is an optional value: `authorization` (IP whitelist),
`coupon`, and `customTargetName` where the section does not require it:

```java
// mobile: operator id (or tag) positionally, rotation in minutes, "0" = By Link
api.orderCalcMobile("USA", "1m", 1L, null, null, "OPERATOR_ID", "5", "dedicated");
api.orderMakeMobile("USA", "1m", 1L, null, null, "OPERATOR_ID", "0", "shared");

// mix, by package id or by package tag
api.orderCalcMixById("MIX_OBJECT_ID", "1m", 100L, null, null, null);
api.orderCalcMixByCode("MIX_PACKAGE_TAG", "1m", 100L);

// ipv4/isp with explicit Uptime; customTargetName is mandatory here
api.orderCalcIpv4("USA", "1m", 5L, null, null, "Research", true);

// resident: the same argument takes the tariff id or its code
api.orderCalcResident("TARIF_ID", null);
```

`OrderOptions` is for the fields that have no positional argument — a per-request
payment system, `generateAuth` for a single `order/make`, `protocol`, `uptime`,
or a mix selected through `countryId`:

```java
OrderOptions order = new OrderOptions();
order.sectionCode = "mobile";
order.countryId = "USA";           // id or code, same field
order.periodId = "1m";
order.operatorId = "OPERATOR_ID";  // id or operator tag
order.rotationId = "5";            // minutes, "0" = By Link
order.mobileServiceType = "dedicated"; // shared or dedicated
order.quantity = 5L;
order.paymentCode = "balance";     // this request only, ignores setPaymentCode
order.generateAuth = "Y";          // order/make only, ignores setGenerateAuth
api.orderMake(order);
```

The `*Code` fields are still there for when you want to be explicit: setting one
drops the corresponding `*Id` from the payload. `rotationCode` is the exception —
the server does not resolve it, so use `rotationId` (see
[Mobile rotation](#mobile-rotation-is-a-number-of-minutes)).

## Listing orders

`orderList()` has no mandatory argument and ten optional filters, so it takes
`OrderListOptions` rather than a positional form that would be ten `null`s at every
call site — the same reason `OrderOptions` exists:

```java
OrderListOptions filters = new OrderListOptions();
filters.status = "PAYED";        // PAYED | NOT_PAYED | RETURN — the status_type of the response
filters.sortBy = "date_insert";  // date_insert | summ | status
filters.order = "desc";
filters.page = 1;
filters.limit = 20;
Map orders = api.orderList(filters);

Map all = api.orderList();       // the same call with no filters at all
```

Query filters and response fields of `order/list` use snake_case names — `order_id`,
`start_date`, `end_date`, `status`, `is_extend`, `auto_order`, `page`, `limit`, `sort_by`,
`order` — not the camelCase of `proxy/list`.

`data` is not a flat list but a `metadata` + `items` pair, and `metadata` is always
there: without `limit` it reports `total_pages = 1`, `current_limit = 0` and the whole
list in `items`. `summ` and `items[].price` are **strings with the currency already in
them** (`$25.00`), `auto_order` and `is_extend` are `Y`/`N` rather than booleans, and
the dates are ISO 8601 with offset (`2026-09-01T14:15:26+00:00`). `id` is a numeric order
ID sent as a string; the ObjectId is `order_id` — the same value `proxyList()` returns as
`order_id`, and the one that renews ipv6, mix and mix_isp (see
[Renewing proxies](#renewing-proxies)).

## Renewing proxies

What you pass follows the proxy type, and every value comes straight out of `proxyList()`:

| type | what to pass | sent as |
|---|---|---|
| `ipv4`, `isp` | the `ip` field (`1.2.3.4`), or the proxy `id` | `ips` / `ids` |
| `mobile` | `ip` + `:` + `port_http` + `:` + `port_socks`, or the proxy `id` | `ips` / `ids` |
| `ipv6`, `mix`, `mix_isp` | the `order_id` field — `orderList()` returns it too | `orderIds`, instead of `ids` |

ipv4, isp and mobile renew per proxy: by `ids` — the proxy `id`, the same field as before — or
by `ips`, the addresses themselves, with no ids to look up:

```java
Map list = (Map) api.proxyList("ipv4");
List<Map> items = (List<Map>) list.get("items");

List<String> ips = new ArrayList<>();
for (Map item : items) {
    ips.add((String) item.get("ip"));       // ["1.2.3.4", "5.6.7.8"]
}

api.prolongCalc("ipv4", ips, "1m", null);   // price first
api.prolongMake("ipv4", ips, "1m", null);   // deducts money
```

`prolongCalc` shows the price; `prolongMake` charges the balance. The period takes a code
(`"1m"`), same fallback as `order/*`, and the fourth argument is a coupon.

The list overloads route every value by its shape and by the type. A value with a dot or a colon
is an address and goes out as `ips`; any other value is an id — `ids` for ipv4, isp and mobile,
`orderIds` for ipv6, mix and mix_isp. Blank values are dropped, and an empty field is not sent at
all.

For ipv4, isp and mobile pass either ids or addresses in one call, not both. When a request
carries both `ids` and `ips`, the server renews by `ids` and ignores the addresses, so they
would silently drop out of a paid renewal. The SDK therefore throws an `IllegalArgumentException`
before anything is sent:

```
Mixing proxy ids and addresses in one call is not supported: pass either ids or addresses
```

For ipv6, mix and mix_isp a mixed list is still routed — ids to `orderIds`, addresses to `ips` —
and the server rejects the address part itself (see below).

### ipv6, mix and mix_isp are renewed as whole orders by `orderIds`

These products are sold and renewed only as whole orders, so they are selected by `orderIds` — the
`order_id` field — instead of `ids`, not by address. Every active proxy of that type in the given
orders is renewed — for `mix` and `mix_isp`, the mix packages of those orders:

```java
Set<String> orders = new LinkedHashSet<>();
for (Map item : (List<Map>) ((Map) api.proxyList("ipv6")).get("items")) {
    orders.add((String) item.get("order_id"));   // many proxies, one order
}

api.prolongCalc("ipv6", new ArrayList<>(orders), "1m", null);
api.prolongMake("ipv6", new ArrayList<>(orders), "1m", null);
```

If any of the orders is not yours or has no active proxy of that type — or no order is given at
all — the whole request fails with `Incorrect orderIds` (code 29) and nothing is renewed. A
selection field of the other kind is an error too (code 0), and the message names the field:
proxy ids in `ids` sent for these types get
`[ids] is not applicable for ipv6: prolong by [orderIds]`, addresses in `ips` get the same with
`[ips]`, and `orderIds` sent for ipv4, isp or mobile gets
`[orderIds] is not applicable for ipv4: prolong by [ids]`.

### What `prolongMake` returns

```json
{"orderId": "6a248de4717805635cf6057d",
 "orderIds": ["6a248de4717805635cf6057d", "6a248de4717805635cf6058a"],
 "total": 25.00,
 "listBaseOrderNumbers": ["NS_1790059585687-no", "NS_1790059601234-kq"],
 "balance": 100.50}
```

`orderIds` lists every renewed order — one request can renew several — as the same `order_id`
values `proxyList()` and `orderList()` return; `orderId` is the first of them.
`listBaseOrderNumbers` holds one base order number per renewed order or mix package, matching
`base_order_number` in `orderList()`.

If the balance is short, `prolongMake` throws an `ApiException` — it never reports a renewal that
did not happen. The calculation, with the server's `warning`, is in `getResponseData()`.

<details>
<summary>The full payload: <code>ProlongOptions</code></summary>

`ProlongOptions` sets every field directly, with no routing: `ids`, `ips`, `orderIds`,
`coupon`, `periodId` / `periodCode` and a per-request `paymentId` / `paymentCode`.

```java
ProlongOptions prolong = new ProlongOptions();
prolong.orderIds = List.of("ORDER_ID");   // ipv6 / mix / mix_isp; ipv4 / isp / mobile take ids or ips
prolong.periodId = "1m";
prolong.paymentCode = "balance";          // this request only
api.prolongMake("mix", prolong);
```

Blank values are dropped and an empty collection is not sent. Set only one of `ids` and `ips`:
this class sends what you set, and when both arrive the server uses `ids`.

</details>

## Automatic renewal

`prolong/make` charges you now. `autoprolong/*` only arms a charge that happens later, without
you present — so it is a separate branch of the API, not a flag on `prolong`. The selection is
the same as for renewal: `ids` or `ips` for ipv4, isp and mobile, `orderIds` for ipv6, mix and
mix_isp (see [Renewing proxies](#renewing-proxies)).

```java
AutoProlongOptions auto = new AutoProlongOptions();
auto.ips = List.of("1.2.3.4");                  // or auto.ids = List.of("PROXY_ID")
auto.periodId = "1m";
auto.paymentId = "balance";                     // mandatory here

api.autoProlongCalc("ipv4", auto);              // what will be charged, and when
api.autoProlongEnable("ipv4", auto);            // arm it
api.autoProlongDisable("ipv4", auto);           // disarm it
```

Shorter forms take the values directly and route them exactly like `prolongCalc` — including the
`IllegalArgumentException` for a list that mixes proxy ids and addresses on ipv4, isp and mobile:

```java
api.autoProlongCalc("ipv4", List.of("1.2.3.4"), "1m");
api.autoProlongEnable("ipv4", List.of("1.2.3.4"), "1m");
api.autoProlongDisable("ipv4", List.of("1.2.3.4"));

api.autoProlongEnable("mix", List.of("ORDER_ID"), "1m");   // order_id → orderIds
```

`paymentId` is **mandatory** for `calc` and `enable` — the charge happens while you are away, so
the payment system cannot be guessed. Only `balance` and `paddle_subscription` are accepted: a
one-off Paddle checkout needs a browser redirect a headless client cannot complete. With
`paddle_subscription` also set `subscriptionId`.

Residential packages renew as a package, not as addresses — send no selection at all:

```java
api.autoProlongCalcResident();                  // or ...Resident("tarif-code") to confirm the tariff
api.autoProlongEnableResident();
api.autoProlongDisableResident();
```

Any selection with `resident` — a non-empty list, `ids`, `ips` or `orderIds` — throws an
`IllegalArgumentException` before anything is sent:

```
resident auto-prolong applies to the whole package: do not pass proxy or order ids
```

The server refuses such a body too: any of `ids`, `ips` and `orderIds` gets
`[ids] is not applicable for resident: auto-prolong applies to the whole package`. The selection
is never dropped silently: a disable meant for a few addresses would otherwise switch off
automatic renewal of the whole package.

Three things about the answers are worth knowing before you parse them:

* **`ids` is not an echo.** `enable` and `disable` answer `ids[]` — the proxies actually
  affected — and `orderIds[]`, their orders (both empty for resident). For ipv6, mix and mix_isp
  the whole order is switched at once, so `quantity` and `ids` cover every active proxy of the
  orders you sent.
* **Not enough money is not an exception.** `calc` answers `status: "error"` with a *filled*
  `data` block and an empty `errors[]` — the same shape `prolong/calc` uses. Read `warning`.
* **Residential fills different fields.** `days` and `chargeDate` are null there (a package
  renews on expiry *or* on traffic exhaustion, so no single date describes it); `tarifId` and
  `dateEnd` carry the meaning instead.

`scraper` has no auto-renewal: it is extended by buying traffic through `order/make`, and the
endpoint answers `Create new order to add traffic, prolong options not available`.

> Replaces `resident/autorenew/{enable,disable,calculate}`, which have been **removed** from the
> server. The body is the same apart from the field spelling.

## Balance

`balance/add` accepts **paymentId only** — unlike `order/*` and `prolong/*` it
does not resolve stable payment codes. Take the id from `balancePaymentsList()`: an id is
unavoidable here, because several top-up systems share one internal code (a single
`cryptomus` covers "USDT (TRC-20)", "All cryptocurrencies" and more). Setting only
`setPaymentCode(...)` and calling `balanceAdd` fails locally with a message saying so,
instead of sending `paymentId: null`.

```java
System.out.println(api.balanceAdd(25.0, "PAYMENT_SYSTEM_OBJECT_ID"));   // payment page url
```

Pass the top-up system per call. `setPaymentId(...)` followed by `balanceAdd(25.0)` works
too, but that setting is also the payment system of every later order and renewal, which
accept only `balance` or `paddle_subscription`.

### Auto top-up

`balance/autotopup/get` returns the configuration and the live state;
`balance/autotopup/set` saves it and answers with the same payload, so no second
read is needed.

```java
Map state = api.balanceAutoTopupGet();
// configured, enabled, state, threshold, amount, subscriptionId, paymentMethod,
// failCount, lastAttemptAt, lastEvent
```

`state` is one of `NO_PAYMENT_METHOD`, `DISABLED`, `ACTIVE`, `PAYMENT_INVALID`,
`PAUSED_FAILURES`.

`set` is a **partial update**: a field you do not set is not sent at all and
keeps its stored value. `AutoTopupOptions` enforces that — it never turns an
unset field into a JSON null.

```java
AutoTopupOptions autoTopup = new AutoTopupOptions();
autoTopup.threshold = new java.math.BigDecimal("15");   // only the threshold changes
api.balanceAutoTopupSet(autoTopup);

api.balanceAutoTopupSet(false);                          // just switch it off
```

Validation is entirely server side and runs against the **merged** result, so a
locally valid partial request can still be rejected. Rejections arrive as
business codes 49-53 and 56:

| code | meaning |
|---|---|
| 49 | auto top-up is not available (feature switched off on the server) |
| 50 | threshold below the minimum (`customData.minThreshold`) |
| 51 | amount below the minimum (`customData.minAmount`) |
| 52 | amount does not cover the threshold |
| 53 | no saved payment method |
| 56 | the saved card has expired |

> Codes 54 and 55 belonged to `dailyCountCap` / `monthlyAmountCap`, removed from the contract on
> 2026-08-18. They are not reused, and `customData` no longer carries `minDailyCountCap`.
> `balanceAutoTopupSet` now rejects both fields locally — the server ignores them, so sending
> them produced a call that reported success and changed nothing.

```java
try {
    api.balanceAutoTopupSet(autoTopup);
} catch (ApiException e) {
    System.out.println(e.getBusinessCode());   // 51
    System.out.println(e.getCustomData());     // {minAmount=5}
}
```

## Proxies

### Replacement

`proxy/replace` takes the **reason** of the replacement in `type` — it is *not*
the proxy type, which the server derives from the ids. Allowed values (also in
`Api.PROXY_REPLACE_TYPES`): `NOT_WORK`, `INCORRECT_LOCATION`,
`CANT_CHANGE_NETWORK`, `LOW_SPEED`, `CUSTOM`. Everything except `CUSTOM` turns
into a canned comment on the server; `CUSTOM` requires a non-blank `comment`.
Both rules are checked locally before the request leaves.

```java
api.proxyReplace(java.util.List.of("IP_ADDRESS_ID"), "NOT_WORK", null);
api.proxyReplace(java.util.List.of("IP_ADDRESS_ID"), "CUSTOM", "blocked by target site");
```

### Exports

`proxy/download/*` returns a raw file body, not an envelope. `ext` is validated
locally (max 250 chars, no CR, LF, `/` or `\`) because the server rejects a bad
value with a bare plain-text HTTP 400 that bypasses the envelope.

`package_key` is honoured by the `subresident` route **only** — the literal
`proxy/download/resident` route ignores it and exports the parent package, so
combining the two throws locally instead of returning the wrong file:

```java
api.proxyDownload("subresident", "txt", "HTTPS", null, "PACKAGE_KEY", null, null);
api.proxyDownloadResident("LIST_ID", "txt", 1000);   // parent package
```

## Resident

`resident/geo` and `resident/geo/isp` return **file attachments** (`geo.json` and
`isp.json`) — plain JSON, not zip archives, whatever older documentation says.
They come back as raw `byte[]`:

```java
java.nio.file.Files.write(java.nio.file.Path.of("geo.json"), api.residentGeo());
```

`resident/traffic/details` requires the package key, and the field is named
`packageKey` (short alias `key`) — **not** `package_key`, which is what the
`residentsubuser/*` endpoints use. Optional filters: `login`, `date_start`,
`date_end`.

```java
api.residentTrafficDetails("PACKAGE_KEY");
api.residentTrafficDetails(java.util.Map.of(
        "packageKey", "PACKAGE_KEY",
        "date_start", "2026-08-01",
        "date_end", "2026-08-17"));
```

`resident/lists` returns `data` as a **flat array** (there is no `items`
wrapper). `resident/package` uses camelCase keys and returns `expiredAt` as a
string (`dd.MM.yyyy HH:mm:ss`).

### Subpackages

Subpackages support all current fields:

```java
api.residentSubUserUpdate("PACKAGE_KEY", null, 60, null, true, "2026-12-31");
api.residentSubUserListAdd("PACKAGE_KEY", "US list", "127.0.0.1",
        java.util.Map.of("country", "US"),
        java.util.Map.of("ports", 1000, "ext", "txt"), 60);
```

`expired_at` is asymmetric: you **send** a string, but it comes **back** as a date object
`{date, timezone_type, timezone}` — `date` is UTC in `yyyy-MM-dd HH:mm:ss.SSSSSS`,
`timezone_type` is always `3`, `timezone` is always `UTC` — so read the nested `date`:

```java
for (Object pkg : api.residentSubUserPackages()) {
    Map expiredAt = (Map) ((Map) pkg).get("expired_at");
    // {date=2026-12-31 23:59:59.000000, timezone_type=3, timezone=UTC}
    System.out.println(expiredAt.get("date"));
}
```

The delete endpoints answer with `data` as a JSON **string**
(`{"status":"delete"}`); the SDK parses it back into a map for you.

## Error handling

Client API v2 answers with **HTTP 200 almost always** — business failures live
inside the envelope `{status, data, errors}`. The API itself never answers HTTP 429:
an exceeded rate limit is a 200 as well (see
[the access-error triple](#access-failures-come-as-a-fixed-triple)). A 429 can only
come from the edge in front of the API, and the client retries it for you — see
[Rate limits and the request queue](#rate-limits-and-the-request-queue). Never branch
on the HTTP status alone.

The SDK turns any `status:"error"` envelope into an `ApiException` carrying the
business code, `customData`, the HTTP status, the raw body, the `data` field and
**the complete `errors` array**.

```java
try {
    api.authChange("AUTH_ID", false);
} catch (ApiException e) {
    System.out.println(e.getBusinessCode());   // e.g. 46
    System.out.println(e.getCustomData());
    System.out.println(e.getHttpStatus());     // usually 200
    System.out.println(e.getResponseBody());
    for (java.util.Map<String, Object> error : e.getErrors()) {
        System.out.println(error.get("code") + " " + error.get("message"));
    }
}
```

### Access failures come as a fixed triple

A wrong api key, an IP outside the allowlist and an exceeded rate limit are
**not** distinguished by status or by a single error. All three arrive as HTTP
200 with the same three-element array, every entry with code `503`:

```json
{"status":"error","data":null,"errors":[
  {"message":"Error api key","code":503},
  {"message":"IP not allowed 10.0.0.1","code":503},
  {"message":"Request limit reached","code":503}
]}
```

`errors[0].message` is always `"Error api key"`, so reading only the first error
tells you nothing about which of the three actually happened — walk
`getErrors()`. `ApiException.isAccessError()` recognises the triple:

```java
} catch (ApiException e) {
    if (e.isAccessError()) {
        // wrong key, IP not allowed, or rate limited — check the full array
        e.getErrors().forEach(err -> System.out.println(err.get("message")));
    }
}
```

The client never retries this triple on its own: an exceeded limit cannot be told apart from a
wrong key or IP.

### Other shapes

* A calculation response with `status:"error"`, non-null `data` and an empty
  `errors` array is an actionable warning (for example insufficient funds on
  `prolong/calc`): that `data` is returned normally, not thrown.
* Proxy exports return raw text; resident geo/ISP downloads return `byte[]`.
* Responses that are not our envelope (a bare framework error page, a plain-text
  400) are reported with their own message — the SDK only treats a body with a
  **string** `status` as an envelope.

## Rate limits and the request queue

The client paces its own requests, so a program that calls the API in a loop stays under the
limits without any code of its own. This is **on by default**.

Every call belongs to one of three categories, decided by its endpoint — not by the HTTP method:
the calculations are POST requests, but they change nothing.

* **money** — `order/make`, `prolong/make/{type}`, `balance/add`: the `orderMake*` methods,
  `prolongMake` and `balanceAdd`.
* **write** — `autoprolong/enable/{type}`, `autoprolong/disable/{type}`, `auth/add`,
  `auth/add/ip`, `auth/change`, `auth/delete`, `proxy/replace`, `proxy/comment/set`,
  `balance/autotopup/set`, `resident/list/{add,delete,rename,rotation,tools}`,
  `residentsubuser/{create,update,delete}` and
  `residentsubuser/list/{add,delete,rename,rotation,tools}`: `autoProlongEnable*`,
  `autoProlongDisable*`, `authAdd`, `authAddIp`, `authChange`, `authDelete`, `proxyReplace`,
  `proxyCommentSet`, `balanceAutoTopupSet`, `residentListAdd` / `Rename` / `Rotation` /
  `Tools` / `Delete`, `residentSubUserCreate` / `Update` / `Delete` and
  `residentSubUserListAdd` / `Rename` / `Rotation` / `Tools` / `Delete`.
* **read** — everything else: every list and get, `reference/list`, `proxy/download/*`,
  `resident/package`, `resident/lists`, `resident/geo*`, `resident/consumption`,
  `resident/traffic/details`, `residentsubuser/packages`, `residentsubuser/lists`, and all
  calculations — `order/calc`, `prolong/calc/{type}`, `autoprolong/calc/{type}`.

What the client does, with the defaults:

1. **One window for everything.** At most `requestsPerMinute` (**1000**) requests start within
   any 60 seconds — reads, writes and money calls together. It is a sliding window, not a token
   bucket, so there is no burst on top of it: a request that would be the 1001st start within
   60 seconds waits until the oldest of those starts is 60 seconds old.
2. **One queue for writes and money.** Write and money calls go one at a time: the next one
   starts only after the previous one has finished, and no earlier than `writeIntervalMillis`
   (**1000 ms**) after the previous write or money call started. A money call also waits until
   `moneyIntervalMillis` (**2000 ms**) have passed since the previous money call started — right
   after a write it starts at whichever of the two ends later. Reads never wait for this queue,
   only for the window.
3. **HTTP 429 is retried.** A 429 comes from the edge in front of the API: the request never
   reached the API, so repeating it is safe even for a money call. The client waits
   `Retry-After` (seconds or an HTTP date; **2 s** when the header is missing or unreadable;
   never more than **60 s**) and sends the request again, up to `maxRetries` (**3**) times.
   After that the call fails with the usual `ApiException`, and `getHttpStatus()` is `429`. A
   write or money call keeps its place in the queue while it is retried — nothing queued behind
   it goes first — and every retry counts as a new start in the window.
4. **Nothing else is retried.** Business errors reach you exactly as before, in particular:
   * code **57**, `Prolong for this order is already in progress` — repeating a renewal on its
     own could extend the order twice;
   * the [access-error triple](#access-failures-come-as-a-fixed-triple) (code 503, one of its
     entries `Request limit reached`) — it cannot be told apart from a wrong api key or an IP
     outside the allowlist.

   Other HTTP errors and network failures are not retried either.

Waiting blocks the calling thread (a plain sleep, no busy loop). Calls from several threads on
one `Api` instance are fine: their write and money calls are serialized in the queue, and their
reads run in parallel.

### Changing or disabling it

The settings live on `Config`. They are read on every request, so they can also be changed on a
live client through `api.getConfig()`:

```java
Config config = new Config("YOUR_API_KEY");
config.setRequestsPerMinute(600);       // default 1000
config.setWriteIntervalMillis(1_500);   // default 1000
config.setMoneyIntervalMillis(3_000);   // default 2000
config.setMaxRetries(5);                // default 3; 0 = fail on the first 429
Api api = new Api(config);
```

```java
config.setRateLimitEnabled(false);      // default true
```

With pacing disabled the client behaves exactly as it did without the queue: every request goes
out at once, and an HTTP 429 fails immediately with `getHttpStatus() == 429`.

### One queue per `Api` instance

The queue lives in the `Api` object, and `setConfig(...)` keeps it. Two `Api` instances — or two
processes — using the same api key know nothing about each other, and together they can exceed
the limits that each of them keeps to. Share one instance between the threads of a process
instead of creating one per thread or per task.

When several processes do share a key, the server can still answer code 57 or the access-error
triple. Handle both as you would without the queue — the client passes them through untouched.

## Platform support

JVM only, JDK 17+.

**Android is not supported.** Four endpoints — `auth/delete`,
`resident/list/delete`, `residentsubuser/delete` and
`residentsubuser/list/delete` — are DELETE requests that carry a JSON body. The
Android implementation of `HttpURLConnection` refuses to write a body on DELETE
(`ProtocolException: DELETE does not support writing`), so those calls cannot
work there.

## Local build

JDK 21 is supported by the Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat clean build
```

Regenerate the jars in `dist/`:

```powershell
.\gradlew.bat build_standalone
```

## Migrating from 1.x

Version 2.0 targets Client API v2 and is **not** backwards compatible. See
[Identifiers are strings](#identifiers-are-strings) first — it is the change that
breaks the most code.

### Renamed and removed

| 1.x | 2.0 |
|---|---|
| `authActive(Integer id, String active)` | `authChange(String id, Boolean active)` |
| `ping()` | removed, no equivalent in v2 |
| `proxyCheck(String proxy)` | removed, no equivalent in v2 |

### Behaviour changes

* `residentListRename`, `residentListRotation` and `residentListDelete` take a `Long` id — resident list ids stayed numeric. `Object` overloads accept the `Double` that Gson hands back.
* `residentListDelete` sends the id in the request body instead of the query string.
* `setGenerateAuth()` only affects `order/make`. The `order/calc` endpoint ignores the field.
* Payment systems are chosen by `setPaymentId(...)` — several of them share one internal code, so a code cannot tell them apart. `setPaymentCode(...)` still works for `order/*` and `prolong/*`, but not for `balanceAdd`, which resolves `paymentId` only.
* `proxyReplace`'s `type` is the replacement reason, not the proxy type.
* `proxyList()` and `referenceList()` without arguments now hit `proxy/list` and `reference/list`.

## Methods available

### Auth
* authList
* authAdd
* authAddIp
* authChange
* authDelete

### Balance
* balance
* balanceAdd
* balancePaymentsList
* balanceAutoTopupGet
* balanceAutoTopupSet

### Reference
* referenceList

### Order
* orderCalcIpv4
* orderCalcIsp
* orderCalcMix
* orderCalcIpv6
* orderCalcMobile
* orderCalcResident
* orderMakeIpv4
* orderMakeIsp
* orderMakeMix
* orderMakeIpv6
* orderMakeMobile
* orderMakeResident
* orderCalc
* orderMake
* orderList

### Prolong
* prolongCalc
* prolongMake

### Automatic renewal
* autoProlongCalc
* autoProlongEnable
* autoProlongDisable
* autoProlongCalcResident
* autoProlongEnableResident
* autoProlongDisableResident

### Proxy
* proxyList
* proxyDownload
* proxyDownloadResident
* proxyReplace
* proxyCommentSet

### Resident
* residentPackage
* residentConsumption
* residentTrafficDetails
* residentGeo
* residentGeoIsp
* residentGeoCount
* residentList
* residentListAdd
* residentListRename
* residentListRotation
* residentListTools
* residentListDelete

### Resident subpackages
* residentSubUserCreate
* residentSubUserUpdate
* residentSubUserDelete
* residentSubUserPackages
* residentSubUserLists
* residentSubUserListAdd
* residentSubUserListRename
* residentSubUserListRotation
* residentSubUserListTools
* residentSubUserListDelete

## Changelog
```
2.0.2
! Behaviour change: requests are now paced by default (see "Rate limits and the request
  queue"): at most 1000 request starts within any 60 s; write and money calls one at a time,
  at least 1 s apart, money calls at least 2 s apart; an HTTP 429 is retried after Retry-After
  up to 3 times. Code 57 and the access-error triple are never retried.
  Config.setRateLimitEnabled(false) restores the previous behaviour
+ Config.setRateLimitEnabled / setRequestsPerMinute / setWriteIntervalMillis /
  setMoneyIntervalMillis / setMaxRetries
! ProlongOptions.orderSeparatorIds / orderSeparatorId removed, the server no longer reads
  them. ipv6, mix and mix_isp are renewed as whole orders through the new
  ProlongOptions.orderIds (order_id from proxyList / orderList) instead of ids
! ids / ips select ipv4, isp and mobile only: ids as before, or the addresses in ips.
  ipv6 is no longer renewed by host:port. A selection field of the other kind is rejected
  by the server, e.g. [ids] is not applicable for ipv6: prolong by [orderIds]
! the List overloads of prolongCalc / prolongMake / autoProlongCalc / autoProlongEnable /
  autoProlongDisable route by type: an id goes out as orderIds for ipv6 / mix / mix_isp
  and as ids for the other types, an address as ips
+ autoprolong/enable and autoprolong/disable answer orderIds[] — the orders of the affected
  proxies — next to ids[]
! a List overload that mixes proxy ids and addresses for ipv4 / isp / mobile now throws
  IllegalArgumentException: the server renews by ids and ignores ips when both arrive, so
  the addresses would silently drop out of a paid renewal
! autoprolong with type resident throws IllegalArgumentException on any selection (a
  non-empty list, ids, ips or orderIds): automatic renewal there covers the whole package,
  and a selection is never dropped silently
! X-Fingerprint is optional: orderMakeResident / orderMakeScraper and orderMake no longer
  throw locally when it is missing, as 2.0.1 did — the header is sent only when set, and the
  server does not refuse API-key orders without it. A call that used to throw now places the order
+ prolong/make answers orderIds[] with every renewed order; orderId is the first of them,
  and listBaseOrderNumbers holds one base order number per renewed order or mix package
+ ProlongOptions no longer sends an empty selection collection or blank values

2.0.1
+ orderList() / orderList(OrderListOptions) for GET order/list. Ten optional filters,
  all of them sent under snake_case names (order_id, start_date, end_date, status,
  is_extend, auto_order, page, limit, sort_by, order). data is a metadata + items pair,
  and summ / items[].price are currency strings, not numbers
+ autoProlongCalc / autoProlongEnable / autoProlongDisable (+ ...Resident variants)
  for autoprolong/{calc,enable,disable}/{type}
+ X-Fingerprint on order/make: Config(key, baseUri, fingerprint), setFingerprint(),
  or a per-call argument. Residential and scraper orders now fail locally without it
  instead of being rejected by the server
+ RequestOptions.getHeaders() — arbitrary request headers
! server removed resident/autorenew/{enable,disable,calculate}; use autoprolong/*/resident
! balanceAutoTopupSet no longer accepts dailyCountCap / monthlyAmountCap (removed from the
  contract 2026-08-18, silently ignored by the server) — passing them now throws
! auto top-up error codes 54 and 55 are gone; customData no longer carries minDailyCountCap
! *Code no longer overrides a paired *Id for mixId, operatorId, rotationId and tarifId —
  the server gives the id priority there, and the SDK was inverting it

2.0
Client API v2. Breaking changes:
! base url moved to /personal/api/v2/, the api key is a path segment
! all ids are strings (MongoDB ObjectId); numeric v1 ids no longer resolve
! authActive -> authChange, the active flag is a boolean
! removed ping and proxyCheck, they do not exist in v2
! residentList* ids stay numeric, residentListDelete sends the id in the body
! generateAuth is only applied to order/make
! proxyList() and referenceList() no longer send a trailing slash
! proxyReplace type is the replacement reason (NOT_WORK / INCORRECT_LOCATION /
  CANT_CHANGE_NETWORK / LOW_SPEED / CUSTOM), not the proxy type
! balanceAdd accepts paymentId only, paymentCode is not resolved there
! a custom baseUri without the api key now fails at construction time
! Android is not supported (DELETE with a body)

New methods:
+ authAdd, authAddIp, authDelete
+ balanceAutoTopupGet, balanceAutoTopupSet
+ proxyReplace, proxyDownloadResident
+ residentConsumption, residentTrafficDetails
+ residentGeoIsp, residentGeoCount
+ residentListRotation, residentListTools
+ residentSubUserCreate, residentSubUserUpdate, residentSubUserDelete
+ residentSubUserPackages, residentSubUserLists
+ residentSubUserListAdd, residentSubUserListRename
+ residentSubUserListRotation, residentSubUserListTools, residentSubUserListDelete
+ ApiException.getErrors() / isAccessError() for the whole errors array
+ Object overloads for resident and subpackage list ids

22.01.2024
Breaking changes:
! remove targetId and targetSectionId from all calc/make requests
! add listId into proxyDownload method

New methods:
+ setPaymentId() - used in all calc/make requests
+ setGenerateAuth() - used in all calc/make requests (Y/N, default N)

+ authList
+ authActive
+ orderCalcResident
+ orderMakeResident
+ residentPackage
+ residentGeo
+ residentList
+ residentListRename
+ residentListDelete
```
