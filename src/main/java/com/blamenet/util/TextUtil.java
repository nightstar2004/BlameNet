package com.blamenet.util;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/** 纯文本处理工具：HTML 转义、长度裁剪、空值归一化、ANSI 清理等。 */
public final class TextUtil {

    /** 证据原始输出的默认最大保留长度；超出部分截断并明确标注，避免报告体积失控。 */
    public static final int DEFAULT_EVIDENCE_LIMIT = 20_000;

    private TextUtil() {
    }

    public static String nvl(String value) {
        return value == null ? "" : value;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /** 将 null 归一化为空串，并统一换行符为 \n。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** HTML 文本转义。 */
    public static String escapeHtml(String value) {
        String text = nvl(value);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 截断超长文本，保留尾部说明；返回结果中会带 Truncated 标记信息。 */
    public static Truncated truncate(String value, int limit) {
        String text = normalize(value);
        if (text.length() <= limit) {
            return new Truncated(text, false, text.length());
        }
        String head = text.substring(0, limit);
        String full = head
                + "\n\n[BlameNet] 原始输出过长，已截断显示：完整长度 " + text.length()
                + " 字符，此处保留前 " + limit + " 字符。完整内容请查看同目录下的 JSON 主数据源。";
        return new Truncated(full, true, text.length());
    }

    /** 去除 ANSI 转义序列，避免进度刷新残留写入报告。 */
    public static String stripAnsi(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
    }

    /** 去掉每行尾部空白与行首尾空行，保留内部结构。 */
    public static String trimLines(String value) {
        String text = normalize(value);
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            sb.append(stripTrailing(line)).append('\n');
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    /** 数字解析：从任意文本中提取整数值，失败返回默认值。 */
    public static long parseLong(String text, long defaultValue) {
        if (text == null) {
            return defaultValue;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double parseDouble(String text, double defaultValue) {
        if (text == null) {
            return defaultValue;
        }
        StringBuilder number = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                number.append(c);
            } else if (c == '.' && !dot) {
                dot = true;
                number.append(c);
            } else if (number.length() > 0) {
                break;
            }
        }
        if (number.isEmpty()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(number.toString()).doubleValue();
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 使用分隔符连接集合，自动跳过 null。 */
    public static String join(Collection<String> values, String delimiter) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(value);
        }
        return sb.toString();
    }

    /** 按模板填充 ${key} 占位符。 */
    public static String fill(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", nvl(e.getValue()));
        }
        return result;
    }

    /** 人类可读的文件大小文本（B / KB / MB / GB）。 */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /** 截断结果载体。 */
    public record Truncated(String text, boolean truncated, int originalLength) {
    }
}
