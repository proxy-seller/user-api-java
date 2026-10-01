package org.proxyseller;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Клиентский темп запросов одного {@link Api}. Правила и умолчания общие для всех пяти SDK:
 * <ol>
 *   <li>общее скользящее окно — не больше {@link Config#getRequestsPerMinute()} стартов за любые
 *       60 с на все запросы вместе. Именно журнал стартов, а не token bucket: bucket пропускает
 *       всплески, в которых за 60 с набирается больше N;</li>
 *   <li>одна полоса на клиента для пишущих и денежных запросов: в полёте не больше одного,
 *       следующий стартует только после завершения предыдущего и не раньше
 *       {@link Config#getWriteIntervalMillis()} от старта предыдущего пишущего/денежного, а
 *       денежный — ещё и не раньше {@link Config#getMoneyIntervalMillis()} от старта предыдущего
 *       денежного. Чтение полосу не ждёт, только окно;</li>
 *   <li>HTTP 429 (лимит на границе перед API — запрос до API не дошёл, повтор безопасен даже для
 *       денег): ждём {@code Retry-After} и повторяем, не больше {@link Config#getMaxRetries()} раз.
 *       Повтор держит место в полосе и считается новым стартом в окне.</li>
 * </ol>
 * Больше ничего не повторяем. Код 57 («Prolong for this order is already in progress») при
 * автоповторе мог бы продлить заказ дважды, а тройка отказа доступа (код 503) неотличима от
 * неверного ключа или IP — обе уходят вызывающему как есть.
 *
 * <p>Состояние живёт в экземпляре: несколько {@code Api} (или процессов) с одним ключом друг
 * о друге не знают. Ожидание блокирует вызывающий поток обычным сном, без активного ожидания.
 * Часы и сон внедряются, чтобы тесты шли мгновенно на поддельном времени.
 */
final class RateLimiter {

    /** Категория запроса — по ПУТИ, а не по HTTP-методу: calc-эндпоинты шлются POST, но только считают. */
    enum Category { READ, WRITE, MONEY }

    /** Сон вызывающего потока; по умолчанию {@link Thread#sleep(long)}. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Одна отправка запроса: каждый вызов — отдельный старт в окне. */
    @FunctionalInterface
    interface Attempt {
        Api.HttpResponse send() throws ApiException;
    }

    static final long WINDOW_MILLIS = 60_000L;
    static final long DEFAULT_RETRY_AFTER_MILLIS = 2_000L;
    static final long MAX_RETRY_AFTER_MILLIS = 60_000L;
    static final int TOO_MANY_REQUESTS = 429;

    private static final String TYPE = "{type}";

    /**
     * ЕДИНСТВЕННАЯ таблица категорий, по ней классифицируется каждый запрос. Всё, чего здесь
     * нет, — чтение: списки и get-эндпоинты, все расчёты ({@code order/calc},
     * {@code prolong/calc/{type}}, {@code autoprolong/calc/{type}}), справочники
     * {@code reference/list}, выгрузки {@code proxy/download/{type}}, резидентские чтения,
     * {@code balance/payments/list}, {@code balance/autotopup/get}.
     *
     * <p>{@code {type}} совпадает с одним сегментом пути — и с его отсутствием: запрос без типа
     * уйдёт в ошибку на сервере, но придержать его в полосе безопаснее, чем выпустить мимо.
     */
    private static final List<Rule> RULES = List.of(
            money("order/make"),
            money("prolong/make/{type}"),
            money("balance/add"),

            write("autoprolong/enable/{type}"),
            write("autoprolong/disable/{type}"),
            write("auth/add"),
            write("auth/add/ip"),
            write("auth/change"),
            write("auth/delete"),
            write("proxy/replace"),
            write("proxy/comment/set"),
            write("balance/autotopup/set"),
            write("resident/list"),                 // POST-алиас resident/list/add
            write("resident/list/add"),
            write("resident/list/delete"),
            write("resident/list/rename"),
            write("resident/list/rotation"),
            write("resident/list/tools"),
            write("residentsubuser/create"),
            write("residentsubuser/update"),
            write("residentsubuser/delete"),
            write("residentsubuser/list/add"),
            write("residentsubuser/list/delete"),
            write("residentsubuser/list/rename"),
            write("residentsubuser/list/rotation"),
            write("residentsubuser/list/tools"));

    private final LongSupplier clock;
    private final LongSupplier wallClock;
    private final Sleeper sleeper;

    /** Полоса пишущих и денежных запросов. Честный замок: ждущие входят по очереди, никто не обгоняет. */
    private final ReentrantLock lane = new ReentrantLock(true);
    private boolean laneStarted;
    private long lastLaneStart;
    private boolean moneyStarted;
    private long lastMoneyStart;

    /**
     * Журнал стартов скользящего окна по возрастанию. Слот занимается сразу, под замком, а ждут
     * его уже снаружи — поэтому журнал может держать и будущие, уже обещанные старты.
     */
    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    /** Боевые часы: монотонные — для темпа, настенные — только для {@code Retry-After} в виде даты. */
    RateLimiter() {
        this(() -> System.nanoTime() / 1_000_000L, System::currentTimeMillis, Thread::sleep);
    }

    /**
     * @param clock     монотонное время в миллисекундах — окно и интервалы
     * @param wallClock миллисекунды эпохи — только для {@code Retry-After} в виде HTTP-date
     * @param sleeper   сон вызывающего потока
     */
    RateLimiter(LongSupplier clock, LongSupplier wallClock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Категория запроса по относительному пути ({@code prolong/make/ipv4}). Регистр, ведущий и
     * хвостовой слеш и строка запроса на результат не влияют.
     *
     * @param path путь эндпоинта относительно корня v2 с ключом
     * @return категория; всё, чего нет в таблице, — {@link Category#READ}
     */
    static Category classify(String path) {
        String[] segments = segments(path);
        for (Rule rule : RULES) {
            if (rule.matches(segments)) {
                return rule.category;
            }
        }
        return Category.READ;
    }

    /**
     * Отправляет запрос по правилам темпа: для полосы — ждёт своей очереди и интервалов, затем
     * слот окна; на HTTP 429 ждёт {@code Retry-After} и повторяет, пока не кончатся повторы.
     * Последний 429 возвращается как есть — его разбирает обычная обработка ошибок.
     *
     * @param category категория запроса, см. {@link #classify(String)}
     * @param config   настройки темпа; читаются один раз на запрос
     * @param attempt  одна отправка
     * @return ответ последней попытки
     * @throws ApiException ошибка отправки, либо поток прерван во время ожидания
     */
    Api.HttpResponse execute(Category category, Config config, Attempt attempt) throws ApiException {
        int requestsPerMinute = config.getRequestsPerMinute();
        int maxRetries = config.getMaxRetries();
        if (category == Category.READ) {
            return sendWithRetries(category, requestsPerMinute, maxRetries, attempt);
        }
        try {
            lane.lockInterruptibly();
        } catch (InterruptedException e) {
            throw interrupted(e);
        }
        try {
            long earliest = Long.MIN_VALUE;
            if (laneStarted) {
                earliest = saturatedAdd(lastLaneStart, config.getWriteIntervalMillis());
            }
            if (category == Category.MONEY && moneyStarted) {
                earliest = Math.max(earliest, saturatedAdd(lastMoneyStart, config.getMoneyIntervalMillis()));
            }
            sleepUntil(earliest);
            return sendWithRetries(category, requestsPerMinute, maxRetries, attempt);
        } finally {
            lane.unlock();
        }
    }

    private Api.HttpResponse sendWithRetries(Category category, int requestsPerMinute, int maxRetries,
                                             Attempt attempt) throws ApiException {
        for (int retry = 0; ; retry++) {
            long start = takeWindowSlot(requestsPerMinute);
            if (category != Category.READ) {
                // Стартом считается КАЖДАЯ отправка, повтор тоже: следующий запрос полосы
                // отсчитывает интервалы от последней реальной отправки, а не от первой попытки.
                laneStarted = true;
                lastLaneStart = start;
                if (category == Category.MONEY) {
                    moneyStarted = true;
                    lastMoneyStart = start;
                }
            }
            Api.HttpResponse response = attempt.send();
            if (response.status != TOO_MANY_REQUESTS || retry >= maxRetries) {
                return response;
            }
            sleepFor(retryAfterMillis(response.retryAfter, wallClock.getAsLong()));
        }
    }

    /**
     * Занимает старт в окне и дожидается его. Слот резервируется под замком, ждут его снаружи:
     * так слоты раздаются по порядку прихода, а окно не перепроверяется в цикле.
     *
     * @param limit стартов за 60 с
     * @return фактическое время старта
     */
    private long takeWindowSlot(int limit) throws ApiException {
        long slot;
        synchronized (starts) {
            long now = clock.getAsLong();
            while (!starts.isEmpty() && starts.peekFirst() <= now - WINDOW_MILLIS) {
                starts.pollFirst();
            }
            // Значимы только последние limit стартов (лимит могли уменьшить у живого клиента).
            while (starts.size() > limit) {
                starts.pollFirst();
            }
            slot = starts.size() < limit ? now : starts.peekFirst() + WINDOW_MILLIS;
            if (!starts.isEmpty()) {
                slot = Math.max(slot, starts.peekLast());
            }
            starts.addLast(slot);
        }
        sleepUntil(slot);
        return Math.max(slot, clock.getAsLong());
    }

    private void sleepFor(long millis) throws ApiException {
        if (millis > 0) {
            sleepUntil(saturatedAdd(clock.getAsLong(), millis));
        }
    }

    /** Спит до момента target; цикл нужен только на случай раннего пробуждения. */
    private void sleepUntil(long target) throws ApiException {
        long now;
        while (target > (now = clock.getAsLong())) {
            long remaining = target - now;
            try {
                sleeper.sleep(remaining > 0 ? remaining : Long.MAX_VALUE);
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    /** Для тестов: стоит ли кто-то в очереди полосы. */
    boolean hasQueuedLaneCallers() {
        return lane.hasQueuedThreads();
    }

    /**
     * {@code Retry-After} → пауза до повтора: целое число секунд или HTTP-date; заголовка нет
     * или его не разобрать — 2 с; в любом случае не больше 60 с.
     *
     * @param header         значение заголовка, может быть null
     * @param nowEpochMillis текущее время эпохи — для формы с датой
     * @return пауза в миллисекундах, от 0 до {@link #MAX_RETRY_AFTER_MILLIS}
     */
    static long retryAfterMillis(String header, long nowEpochMillis) {
        String value = header == null ? "" : header.trim();
        if (value.isEmpty()) {
            return DEFAULT_RETRY_AFTER_MILLIS;
        }
        if (isDigits(value)) {
            // Длинное число сразу упирается в потолок — без переполнения при разборе.
            return value.length() > 5
                    ? MAX_RETRY_AFTER_MILLIS
                    : Math.min(Long.parseLong(value) * 1000L, MAX_RETRY_AFTER_MILLIS);
        }
        try {
            long at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(0L, Math.min(at - nowEpochMillis, MAX_RETRY_AFTER_MILLIS));
        } catch (DateTimeParseException e) {
            return DEFAULT_RETRY_AFTER_MILLIS;
        }
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        // Переполнение — только когда знаки слагаемых совпадают, а знак суммы другой.
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    private static ApiException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new ApiException("Interrupted while waiting in the client-side request queue", null, null, e);
    }

    private static String[] segments(String path) {
        String value = path == null ? "" : path;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        List<String> result = new ArrayList<>();
        for (String segment : value.toLowerCase(Locale.ROOT).split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result.toArray(new String[0]);
    }

    private static Rule money(String pattern) {
        return new Rule(pattern, Category.MONEY);
    }

    private static Rule write(String pattern) {
        return new Rule(pattern, Category.WRITE);
    }

    /** Строка таблицы: путь по сегментам, последний может быть {@code {type}}. */
    private static final class Rule {
        private final String[] fixed;
        private final boolean typed;
        private final Category category;

        private Rule(String pattern, Category category) {
            String[] parts = pattern.split("/");
            this.typed = TYPE.equals(parts[parts.length - 1]);
            this.fixed = typed ? Arrays.copyOf(parts, parts.length - 1) : parts;
            this.category = category;
        }

        private boolean matches(String[] path) {
            if (path.length != fixed.length && !(typed && path.length == fixed.length + 1)) {
                return false;
            }
            for (int i = 0; i < fixed.length; i++) {
                if (!fixed[i].equals(path[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
