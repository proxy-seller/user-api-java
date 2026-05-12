# proxy-seller java api

Install from [maven-central](https://mvnrepository.com/artifact/com.proxy-seller/proxy-seller-user-api/)
```gradle
implementation 'com.proxy-seller:proxy-seller-user-api:1.1'
```

Download jar from [https://github.com/proxy-seller/user-api-java/tree/main/dist](https://github.com/proxy-seller/user-api-java/tree/main/dist) repository

## Quick start
Get API key [here](https://proxy-seller.com/personal/api/)
```java
import org.proxyseller.Api;
import org.proxyseller.Config;

public class Proxy {

    public static void main(String[] args) throws Exception {
        Api api = new Api(new Config("YOUR_API_KEY"));
//        api.setPaymentId(1);
//        api.setGenerateAuth("N");
        System.out.println(api.ping());
    }
}
```

## Changelog
```
22.01.2024
Breaking changes:
! remove targetId and targetSectionId from all calc/make requests
! add listId into proxyDownload method

New methods:
+ setPaymentId() - used in all calc/make requests (payment id=1(inner balance), id=43(subscribed card))
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

## Methods available:
* authList
* authActive
* balance
* balanceAdd
* balancePaymentsList
* referenceList
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
* prolongCalc
* prolongMake
* proxyList
* proxyDownload
* proxyCommentSet
* proxyCheck
* ping
* residentPackage
* residentGeo
* residentList
* residentListAdd
* residentListRename
* residentListDelete