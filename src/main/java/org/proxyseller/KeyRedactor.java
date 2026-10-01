package org.proxyseller;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Вычищает API-ключ из всего, что SDK отдаёт наружу в ошибках. Ключ в v2 — сегмент пути, и любой
 * ответ, повторяющий путь (404 фреймворка с полем {@code path}, страница эджа, текст исключения
 * сервера), унёс бы его в логи вызывающего вместе с {@link ApiException}.
 *
 * <p>Ищется точное значение, его URL-кодированные формы (сегмент пути — пробел как {@code %20};
 * query-string — как {@code +}) и всё это без учёта регистра: фронт-404 стейджа отдаёт путь в
 * нижнем регистре, а шестнадцатеричные коды кодирования бывают в обоих регистрах.
 */
final class KeyRedactor {

    /** Чем заменяется ключ. */
    static final String MASK = "***";

    /** Глубже не копируем: и конверт, и цепочка причин мельче. */
    private static final int MAX_DEPTH = 32;

    /** null — маскировать нечего (ключ не задан). */
    private final Pattern pattern;

    /**
     * @param keys ключи, которые нельзя выпускать наружу; null и пустые пропускаются
     */
    KeyRedactor(String... keys) {
        Set<String> forms = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
            forms.add(key);
            forms.add(encoded);
            forms.add(encoded.replace("+", "%20"));
        }
        // Длинные формы — первыми: иначе короткая совпала бы с началом длинной и хвост остался бы виден.
        List<String> ordered = new ArrayList<>(forms);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        this.pattern = ordered.isEmpty() ? null : Pattern.compile(
                ordered.stream().map(Pattern::quote).collect(Collectors.joining("|")),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * @param text любой текст
     * @return тот же текст с ключом, заменённым на {@link #MASK}; без совпадений — тот же экземпляр
     */
    String redact(String text) {
        if (text == null || pattern == null) {
            return text;
        }
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(MASK));
    }

    /** Есть ли в тексте ключ в любой из форм. */
    boolean leaks(String text) {
        return text != null && pattern != null && pattern.matcher(text).find();
    }

    /**
     * Маскирует ключ во всех строках разобранного JSON — ключах и значениях карт, элементах
     * списков. Копия делается только там, где ключ нашёлся, остальное отдаётся как есть.
     *
     * @param value Map, Collection, String или скаляр
     * @return значение без ключа
     */
    Object redactValue(Object value) {
        return redactValue(value, 0);
    }

    private Object redactValue(Object value, int depth) {
        if (pattern == null || value == null || depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof String) {
            return redact((String) value);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey() instanceof String ? redact((String) entry.getKey()) : entry.getKey();
                Object item = redactValue(entry.getValue(), depth + 1);
                changed |= key != entry.getKey() || item != entry.getValue();
                copy.put(key, item);
            }
            return changed ? copy : value;
        }
        if (value instanceof Collection) {
            Collection<?> items = (Collection<?>) value;
            List<Object> copy = new ArrayList<>(items.size());
            boolean changed = false;
            for (Object item : items) {
                Object redacted = redactValue(item, depth + 1);
                changed |= redacted != item;
                copy.add(redacted);
            }
            return changed ? copy : value;
        }
        return value;
    }

    /**
     * Причина для {@link ApiException}: исходное исключение, если в его тексте (вместе с цепочкой
     * причин) ключа нет, — тогда {@code getCause() instanceof SocketTimeoutException} и прочие
     * проверки работают как прежде. Если ключ есть (например, {@code MalformedURLException} целиком
     * повторяет URL), цеплять его нельзя: отдаём {@link IOException} с тем же стеком, именем
     * исходного класса и замаскированным текстом.
     *
     * @param error исходное исключение
     * @return исключение без ключа в тексте
     */
    Throwable sanitize(Throwable error) {
        if (error == null || pattern == null || !leaks(describe(error))) {
            return error;
        }
        return copy(error, 0);
    }

    private Throwable copy(Throwable error, int depth) {
        String message = error.getMessage();
        IOException copy = new IOException(error.getClass().getName()
                + (message == null ? "" : ": " + redact(message)));
        copy.setStackTrace(error.getStackTrace());
        Throwable cause = error.getCause();
        if (cause != null && cause != error && depth < MAX_DEPTH) {
            copy.initCause(leaks(describe(cause)) ? copy(cause, depth + 1) : cause);
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (depth < MAX_DEPTH) {
                copy.addSuppressed(leaks(describe(suppressed)) ? copy(suppressed, depth + 1) : suppressed);
            }
        }
        return copy;
    }

    /** Полный текст исключения так, как его напечатал бы логгер: с причинами и подавленными. */
    private static String describe(Throwable error) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            error.printStackTrace(writer);
        }
        return text.toString();
    }
}
