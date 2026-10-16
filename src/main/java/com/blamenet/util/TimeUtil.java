package com.blamenet.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具。
 * <p>
 * 约定：所有写入 JSON 的时间戳均为 UTC 的 ISO-8601 形式（如 2026-09-30T14:22:18.652Z），
 * 与设计要求中的示例保持一致；报告编号（文件名）使用本机本地时间，便于用户按时间查找文件。
 */
public final class TimeUtil {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter LOCAL_HUMAN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeUtil() {
    }

    /** 当前时刻的 UTC ISO-8601 字符串，毫秒精度。 */
    public static String isoNow() {
        return ISO_UTC.format(Instant.now());
    }

    /** 指定时刻的 UTC ISO-8601 字符串。 */
    public static String iso(Instant instant) {
        return ISO_UTC.format(instant);
    }

    /** 报告编号用的本地时间戳：yyyy-MM-dd-HH-mm-ss。 */
    public static String stamp(Instant instant) {
        return LOCAL_STAMP.format(ZonedDateTime.ofInstant(instant, ZoneOffset.systemDefault()));
    }

    /** 人类可读的本地时间：yyyy-MM-dd HH:mm:ss。 */
    public static String human(Instant instant) {
        return LOCAL_HUMAN.format(ZonedDateTime.ofInstant(instant, ZoneOffset.systemDefault()));
    }

    /** 耗时的展示形式：HH:mm:ss.SSS，例如 00:00:27.465。 */
    public static String duration(Duration duration) {
        return duration(duration.toMillis());
    }

    /** 耗时的展示形式：HH:mm:ss.SSS。 */
    public static String duration(long millis) {
        long ms = Math.max(0L, millis);
        long hours = ms / 3_600_000L;
        long minutes = (ms % 3_600_000L) / 60_000L;
        long seconds = (ms % 60_000L) / 1000L;
        long millisPart = ms % 1000L;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millisPart);
    }

    /** 简短耗时展示：12.3 秒 / 1 分 05 秒。用于界面提示，不写入 JSON。 */
    public static String humanDuration(long millis) {
        long ms = Math.max(0L, millis);
        if (ms < 1000L) {
            return ms + " 毫秒";
        }
        if (ms < 60_000L) {
            return String.format("%.1f 秒", ms / 1000.0);
        }
        long minutes = ms / 60_000L;
        long seconds = (ms % 60_000L) / 1000L;
        return String.format("%d 分 %02d 秒", minutes, seconds);
    }

    /** 本机时区 ID，例如 Asia/Shanghai。 */
    public static String zoneId() {
        return ZoneOffset.systemDefault().getId();
    }
}
