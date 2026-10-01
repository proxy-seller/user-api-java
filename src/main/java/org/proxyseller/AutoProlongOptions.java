package org.proxyseller;

import java.util.Map;

/**
 * Full payload accepted by {@code autoprolong/calc}, {@code autoprolong/enable} and
 * {@code autoprolong/disable}.
 *
 * <p>Everything {@link ProlongOptions} carries applies here as well — the selection
 * ({@link ProlongOptions#ids} or {@link ProlongOptions#ips} for ipv4/isp/mobile,
 * {@link ProlongOptions#orderIds} for ipv6/mix/mix_isp), the period and the payment system. On
 * top of it automatic extension adds {@link #subscriptionId} and {@link #tarifId}.
 *
 * <p>{@code type = resident} takes no selection at all: automatic extension applies to the whole
 * package. Any of {@code ids}, {@code ips} or {@code orderIds} set for resident is rejected
 * locally with {@link IllegalArgumentException} — never dropped silently, since a disable meant
 * for a few addresses would otherwise switch off the whole package.
 *
 * <p>{@code paymentId} is <b>required</b> by {@code calc} and {@code enable}: the charge happens
 * while the client is not there, so it cannot be guessed. Only {@code balance} and
 * {@code paddle_subscription} are accepted.
 *
 * <p>The server also accepts the snake_case spellings {@code payment_id},
 * {@code subscription_id}, {@code tarif_id} and {@code tariffId}, preferring camelCase when both
 * arrive. This class sends the canonical camelCase names.
 */
public class AutoProlongOptions extends ProlongOptions {
    /**
     * Paddle subscription (saved card) to charge. Only meaningful when the payment system
     * resolves to {@code paddle_subscription}, and ignored for {@code balance}. Optional while
     * the account has one saved card — the server charges it; required when there are several
     * ({@code Set [subscriptionId]}). With no saved card the server answers {@code No saved card
     * on the account: add a card in your account or use [paymentId] balance}. A value that does
     * not belong to the calling account is rejected with
     * {@code Set existed [subscriptionId] from reference}.
     */
    public String subscriptionId;

    /**
     * Resident branch only ({@code type = resident}); regular proxies use
     * {@link ProlongOptions#periodId} instead. Automatic extension always renews the tariff
     * currently on the package and cannot switch tariffs, so the only accepted value is that
     * tariff's own code or id — sending it merely confirms what will be calculated. Any other
     * existing tariff is rejected with {@code Set [tarifId] from package: <code>}, an unknown one
     * with {@code Set existed [tarifId] from reference}.
     */
    public String tarifId;

    @Override
    public Map<Object, Object> toMap() {
        Map<Object, Object> map = super.toMap();
        if (subscriptionId != null) {
            map.put("subscriptionId", subscriptionId);
        }
        if (tarifId != null) {
            map.put("tarifId", tarifId);
        }
        return map;
    }
}
