package cn.keevol.keenotes.mobilefx.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期时间工具类
 * 统一处理日期时间的获取、转换和格式化
 *
 * 规范：
 * 1. 获取当前系统时区的本地时间
 * 2. 转换为 UTC
 * 3. 格式化为 yyyy-MM-dd HH:mm:ss
 *
 * 支持多种输入格式解析（最大化兼容）：
 * - ISO-8601: 2025-12-04T05:21:19Z, 2025-12-04T05:21:19+00:00
 * - 标准格式: 2025-12-04 05:21:19
 * - 混合格式: 2025-12-04T05:21:19 (无时区)
 * - 纯日期: 2025-12-04 (默认 00:00:00)
 */
public class DateTimeUtil {

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 支持的日期时间格式解析器列表（按优先级排序）
    private static final List<DateTimeFormatter> PARSERS = new ArrayList<>();

    static {
        // ISO-8601 格式（带时区）
        PARSERS.add(DateTimeFormatter.ISO_OFFSET_DATE_TIME);  // 2025-12-04T05:21:19+00:00
        PARSERS.add(DateTimeFormatter.ISO_ZONED_DATE_TIME);   // 2025-12-04T05:21:19Z
        PARSERS.add(DateTimeFormatter.ISO_INSTANT);           // 2025-12-04T05:21:19Z

        // 自定义 ISO 格式
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")); // 带毫秒和时区
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));     // 带时区
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));    // 带毫秒无时区
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));        // 标准 ISO 无时区

        // 空格分隔格式
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));       // 带毫秒
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));          // 标准格式
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));             // 精确到分钟

        // 其他常见格式
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd'T'HH:mm:ss"));
        PARSERS.add(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
        PARSERS.add(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        // 纯日期格式（会默认补充 00:00:00）
        PARSERS.add(DateTimeFormatter.ISO_LOCAL_DATE);        // 2025-12-04
        PARSERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        PARSERS.add(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        PARSERS.add(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /**
     * 获取当前时间的 UTC 格式字符串
     * 流程：本地时区时间 -> 转为 UTC -> 格式化为标准格式
     *
     * @return 格式化后的 UTC 时间字符串 (yyyy-MM-dd HH:mm:ss)
     */
    public static String getCurrentUtcTimestamp() {
        // 1. 获取当前系统时区的本地时间
        LocalDateTime localNow = LocalDateTime.now();
        // 2. 转为带系统时区信息
        OffsetDateTime offsetNow = localNow.atZone(ZoneId.systemDefault())
                .toOffsetDateTime();
        // 3. 转为 UTC
        OffsetDateTime utcNow = offsetNow.withOffsetSameInstant(ZoneOffset.UTC);
        // 4. 格式化为标准格式
        return utcNow.format(TS_FORMATTER);
    }

    /**
     * 将输入的日期时间字符串标准化为 UTC 格式
     * 支持多种输入格式，统一输出为 yyyy-MM-dd HH:mm:ss UTC
     *
     * 支持的格式包括：
     * - ISO-8601: 2025-12-04T05:21:19Z, 2025-12-04T05:21:19+00:00
     * - 标准格式: 2025-12-04 05:21:19
     * - 混合格式: 2025-12-04T05:21:19 (无时区)
     * - 纯日期: 2025-12-04 (默认 00:00:00)
     * - 其他格式: 2025/12/04 05:21:19, 04-12-2025 05:21:19 等
     *
     * @param input 输入的时间字符串
     * @return 标准化后的 UTC 时间字符串，解析失败返回当前 UTC 时间
     */
    public static String normalizeToUtc(String input) {
        if (input == null || input.isBlank()) {
            return getCurrentUtcTimestamp();
        }

        String trimmed = input.trim();

        // 尝试使用预定义的解析器列表
        for (DateTimeFormatter formatter : PARSERS) {
            try {
                TemporalAccessor temporal = formatter.parse(trimmed);

                // 根据解析结果类型处理
                if (temporal.isSupported(java.time.temporal.ChronoField.INSTANT_SECONDS)) {
                    // 带时区信息，直接转为 UTC
                    OffsetDateTime odt = OffsetDateTime.from(temporal);
                    return odt.withOffsetSameInstant(ZoneOffset.UTC).format(TS_FORMATTER);
                } else if (temporal.isSupported(java.time.temporal.ChronoField.OFFSET_SECONDS)) {
                    // 带偏移量信息
                    OffsetDateTime odt = OffsetDateTime.from(temporal);
                    return odt.withOffsetSameInstant(ZoneOffset.UTC).format(TS_FORMATTER);
                } else if (temporal.isSupported(java.time.temporal.ChronoField.HOUR_OF_DAY)) {
                    // 本地日期时间（无时区），假设为系统时区
                    LocalDateTime ldt = LocalDateTime.from(temporal);
                    return ldt.atZone(ZoneId.systemDefault())
                            .toOffsetDateTime()
                            .withOffsetSameInstant(ZoneOffset.UTC)
                            .format(TS_FORMATTER);
                } else {
                    // 纯日期，补充 00:00:00
                    LocalDate date = LocalDate.from(temporal);
                    return date.atStartOfDay(ZoneId.systemDefault())
                            .toOffsetDateTime()
                            .withOffsetSameInstant(ZoneOffset.UTC)
                            .format(TS_FORMATTER);
                }
            } catch (DateTimeParseException e) {
                // 继续尝试下一个解析器
                continue;
            }
        }

        // 所有预定义解析器都失败，尝试特殊处理
        try {
            // 尝试作为 Instant（Unix 时间戳秒或毫秒）
            long timestamp = Long.parseLong(trimmed);
            // 判断是秒还是毫秒（简单启发式：大于 10^12 认为是毫秒）
            Instant instant = timestamp > 1_000_000_000_000L
                    ? Instant.ofEpochMilli(timestamp)
                    : Instant.ofEpochSecond(timestamp);
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).format(TS_FORMATTER);
        } catch (NumberFormatException e) {
            // 不是数字时间戳，忽略
        }

        // 所有尝试都失败，返回当前 UTC 时间
        return getCurrentUtcTimestamp();
    }

    /**
     * 尝试解析输入字符串为 LocalDateTime
     * 用于需要获取强类型日期时间的场景
     *
     * @param input 输入的时间字符串
     * @return 解析后的 LocalDateTime，失败返回 null
     */
    public static LocalDateTime tryParse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();

        for (DateTimeFormatter formatter : PARSERS) {
            try {
                TemporalAccessor temporal = formatter.parse(trimmed);

                if (temporal.isSupported(java.time.temporal.ChronoField.HOUR_OF_DAY)) {
                    return LocalDateTime.from(temporal);
                } else {
                    // 纯日期，补充 00:00:00
                    return LocalDate.from(temporal).atStartOfDay();
                }
            } catch (DateTimeParseException e) {
                continue;
            }
        }

        return null;
    }

    /**
     * 将 LocalDateTime 转为 UTC 格式字符串
     * 假设输入是系统时区的本地时间
     *
     * @param localDateTime 本地日期时间
     * @return UTC 格式字符串
     */
    public static String toUtcString(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return getCurrentUtcTimestamp();
        }
        return localDateTime.atZone(ZoneId.systemDefault())
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .format(TS_FORMATTER);
    }

    /**
     * 将带时区的 OffsetDateTime 转为 UTC 格式字符串
     *
     * @param offsetDateTime 带时区的日期时间
     * @return UTC 格式字符串
     */
    public static String toUtcString(OffsetDateTime offsetDateTime) {
        if (offsetDateTime == null) {
            return getCurrentUtcTimestamp();
        }
        return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).format(TS_FORMATTER);
    }

    /**
     * 将 UTC 时间字符串转换为本地时区显示字符串
     * 用于 UI 显示：数据库中存储的是 UTC，显示时转为本地时区
     *
     * @param utcString UTC 时间字符串 (yyyy-MM-dd HH:mm:ss)
     * @return 本地时区格式字符串，解析失败返回原字符串
     */
    public static String utcToLocalDisplay(String utcString) {
        if (utcString == null || utcString.isBlank()) {
            return utcString;
        }

        try {
            // 解析 UTC 时间
            LocalDateTime utcDateTime = LocalDateTime.parse(utcString, TS_FORMATTER);
            // 转为带 UTC 时区的 OffsetDateTime
            OffsetDateTime utc = utcDateTime.atOffset(ZoneOffset.UTC);
            // 转为系统本地时区
            OffsetDateTime local = utc.withOffsetSameInstant(
                    ZoneId.systemDefault().getRules().getOffset(utc.toInstant()));
            // 格式化为显示格式
            return local.format(TS_FORMATTER);
        } catch (Exception e) {
            // 解析失败，返回原字符串
            return utcString;
        }
    }

    /**
     * 将 UTC 时间字符串转换为本地时区显示字符串（带时区偏移信息）
     * 用于需要显示时区信息的场景
     *
     * @param utcString UTC 时间字符串
     * @return 本地时区格式字符串（如：2025-12-04 13:21:19 +08:00），解析失败返回原字符串
     */
    public static String utcToLocalDisplayWithOffset(String utcString) {
        if (utcString == null || utcString.isBlank()) {
            return utcString;
        }

        try {
            LocalDateTime utcDateTime = LocalDateTime.parse(utcString, TS_FORMATTER);
            OffsetDateTime utc = utcDateTime.atOffset(ZoneOffset.UTC);
            OffsetDateTime local = utc.withOffsetSameInstant(
                    ZoneId.systemDefault().getRules().getOffset(utc.toInstant()));
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
            return local.format(formatter);
        } catch (Exception e) {
            return utcString;
        }
    }
}
