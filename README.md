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

Requires **JDK 17+**. **Android is not supported** — see [Platform support](#platform-support).

## Quick start
Get API key [here](https://proxy-seller.com/personal/api/)
```java
import org.proxyseller.Api;
import org.proxyseller.Config;

public class Proxy {

    public static void main(String[] args) throws Exception {
        Api api = new Api(new Config("YOUR_API_KEY"));
//        api.setPaymentId("PAYMENT_SYSTEM_ID");
//        api.setGenerateAuth("N");
        System.out.println(api.balance());
    }
}
```

The api key is a **path segment** in v2
(`https://proxy-seller.com/personal/api/v2/{apiKey}/...`), not a header.

For a local or development Client API, pass the v2 API root. The key is
appended and URL-encoded automatically; a complete per-key URL or a URL with
`{apiKey}` is also accepted:

```java
Config config = new Config("YOUR_API_KEY", "http://localhost:7995/personal/api/v2/");
config.setConnectTimeoutMillis(10_000);
config.setReadTimeoutMillis(30_000);
Api api = new Api(config);
```

A custom `baseUri` that is neither the v2 root nor contains the key is rejected
with an `IllegalArgumentException` at construction time, instead of silently
sending every request without a key.

## Identifiers are strings

Every id in v2 is a MongoDB **ObjectId string**: `countryId`, `periodId`,
`paymentId`, `operatorId`, `rotationId`, `tarifId`, `orderId`, auth ids, IP
address ids. Do not parse them into `int`/`long` — they are not numbers, and the
numeric ids of v1 do not resolve at all.

Where possible, prefer the stable codes over ids — they survive migrations and
are identical in dev and production:

```java
// works, and keeps working
api.orderCalcIpv4("USA", "1m", 5L, null, null, null);
```

Available codes come from `referenceList()`: `countryCode` (`USA`, `FRA`, `BRA`),
`periodCode` (`1w`, `1m`, `3m`), `tarifCode`, `mixCode`, `operatorCode`, `rotationCode`.

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

Use `OrderOptions` for explicit code-first requests. Code values override the
corresponding environment-specific ids:

```java
OrderOptions order = new OrderOptions();
order.sectionCode = "mobile";
order.countryCode = "USA";
order.periodCode = "1m";
order.paymentCode = "balance";
order.operatorCode = "vodafone";
order.rotationCode = "10";
order.mobileServiceType = "dedicated"; // shared or dedicated
order.quantity = 5L;
api.orderCalc(order);
```

MIX has explicit id/code helpers, and IPv4/ISP have Uptime overloads:

```java
api.orderCalcMixById("MIX_OBJECT_ID", "PERIOD_OBJECT_ID", 100L, null, null, null);
api.orderCalcMixByCode("mix-us-eu", "1m", 100L);
api.orderCalcIpv4("COUNTRY_ID", "PERIOD_ID", 5L, null, null, "Research", true);
api.orderMakeMobile("COUNTRY_ID", "PERIOD_ID", 1L, null, null,
        "OPERATOR_ID", "ROTATION_ID", "shared");
```

`ProlongOptions` exposes the complete renewal payload: `ids`,
`orderSeparatorIds`, `orderSeparatorId`, `coupon`, `periodId`, `periodCode`,
`paymentId` and `paymentCode`.

```java
ProlongOptions prolong = new ProlongOptions();
prolong.orderSeparatorIds = java.util.List.of("SEPARATOR_ID");
prolong.periodCode = "1m";
prolong.paymentCode = "balance";
api.prolongCalc("mix", prolong);
```

## Balance

`balance/add` accepts **paymentId only** — unlike `order/*` and `prolong/*` it
does not resolve stable payment codes. Take the id from `balancePaymentsList()`.
Setting only `setPaymentCode(...)` and calling `balanceAdd` fails locally with a
message saying so, instead of sending `paymentId: null`.

```java
api.setPaymentId("PAYMENT_SYSTEM_OBJECT_ID");
System.out.println(api.balanceAdd(25.0));           // payment page url
System.out.println(api.balanceAdd(25.0, "OTHER_PAYMENT_SYSTEM_ID"));
```

### Auto top-up

`balance/autotopup/get` returns the configuration and the live state;
`balance/autotopup/set` saves it and answers with the same payload, so no second
read is needed.

```java
Map state = api.balanceAutoTopupGet();
// configured, enabled, state, threshold, amount, subscriptionId, paymentMethod,
// dailyCountCap, monthlyAmountCap, failCount, lastAttemptAt, lastEvent
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
business codes 49-56:

| code | meaning |
|---|---|
| 49 | auto top-up is not available (feature switched off on the server) |
| 50 | threshold below the minimum (`customData.minThreshold`) |
| 51 | amount below the minimum (`customData.minAmount`) |
| 52 | amount does not cover the threshold |
| 53 | no saved payment method |
| 54 | daily count cap below the minimum (`customData.minDailyCountCap`) |
| 55 | monthly cap does not cover a single top-up |
| 56 | the saved card has expired |

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

`expired_at` is asymmetric: you **send** a string, but it comes **back** as a PHP
date object, so read the nested `date`:

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
inside the envelope `{status, data, errors}`. There is no HTTP 429: an exceeded
rate limit is a 200 as well. Never branch on the HTTP status alone.

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

### Other shapes

* A calculation response with `status:"error"`, non-null `data` and an empty
  `errors` array is an actionable warning (for example insufficient funds on
  `prolong/calc`): that `data` is returned normally, not thrown.
* Proxy exports return raw text; resident geo/ISP downloads return `byte[]`.
* Responses that are not our envelope (a bare framework error page, a plain-text
  400) are reported with their own message — the SDK only treats a body with a
  **string** `status` as an envelope.

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
* Prefer `setPaymentCode("balance")` to a MongoDB payment id when the same code runs in dev and production — except on `balanceAdd`, which resolves `paymentId` only.
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

### Prolong
* prolongCalc
* prolongMake

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
