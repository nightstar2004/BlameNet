package com.blamenet.parse;

import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用文本结构解析。
 * <p>
 * Windows 原生工具（ipconfig / netsh / powercfg / ping / Test-NetConnection 等）输出的是人可读文本，
 * 这里提供三种真实解析能力，不依赖任何预置样例数据：
 * <ul>
 *     <li>{@link #parseListBlocks(String)}：PowerShell Format-List 风格的多组「键 : 值」块；</li>
 *     <li>{@link #parseKeyValues(String)}：单组「键 : 值」；</li>
 *     <li>{@link #splitColumns(String)}：按 2 个以上空格切分表格行。</li>
 * </ul>
 */
public final class TextTable {

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([^:]+?)\\s*:\\s*(.*)$");

    /** 分节标题行，例如 "以太网适配器 以太网:" 或 "Ethernet adapter Ethernet:"。 */
    private static final Pattern SECTION = Pattern.compile(
            "^\\s*(?:以太网适配器|未知适配器|隧道适配器|无线局域网适配器|Ethernet adapter|Unknown adapter|Tunnel adapter|Wireless LAN adapter)\\s+(.+?)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private TextTable() {
    }

    /** 解析 PowerShell Format-List 风格输出：多组「键 : 值」块，块之间以空行分隔。 */
    public static List<Map<String, String>> parseListBlocks(String raw) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : TextUtil.normalize(raw).split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new LinkedHashMap<>();
                }
                continue;
            }
            String[] pair = splitKeyValue(line);
            if (pair != null) {
                current.put(normalizeKey(pair[0]), pair[1].trim());
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    /** 解析单组「键 : 值」行，忽略无法识别的行。 */
    public static Map<String, String> parseKeyValues(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : TextUtil.normalize(raw).split("\n", -1)) {
            String[] pair = splitKeyValue(line);
            if (pair != null) {
                map.put(normalizeKey(pair[0]), pair[1].trim());
            }
        }
        return map;
    }

    /**
     * 解析 ipconfig /all 的适配器分节：返回「分节名 → 键值表」的有序结果。
     * 支持中英文两种系统语言的分节标题。
     */
    public static List<Map.Entry<String, List<Map.Entry<String, String>>>> parseSections(String raw) {
        List<Map.Entry<String, List<Map.Entry<String, String>>>> sections = new ArrayList<>();
        String sectionName = null;
        List<Map.Entry<String, String>> entries = null;
        for (String line : TextUtil.normalize(raw).split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            Matcher section = SECTION.matcher(line);
            if (section.matches() && !line.trim().contains(" : ")) {
                if (sectionName != null) {
                    sections.add(Map.entry(sectionName, entries));
                }
                sectionName = section.group(1).trim();
                entries = new ArrayList<>();
                continue;
            }
            if (sectionName == null) {
                continue;
            }
            String[] pair = splitKeyValue(line);
            if (pair != null) {
                entries.add(Map.entry(normalizeKey(pair[0]), pair[1].trim()));
            } else if (!entries.isEmpty()) {
                // 网关、DNS 等多值项会以缩进续行的形式出现
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && (isIpLike(trimmed) || isIpLike(stripSuffix(trimmed)))) {
                    Map.Entry<String, String> last = entries.remove(entries.size() - 1);
                    entries.add(Map.entry(last.getKey(), last.getValue() + " " + trimmed));
                }
            }
        }
        if (sectionName != null) {
            sections.add(Map.entry(sectionName, entries));
        }
        return sections;
    }

    /** 按 2 个及以上空格切分一行表格数据。 */
    public static List<String> splitColumns(String line) {
        List<String> columns = new ArrayList<>();
        String trimmed = TextUtil.nvl(line).trim();
        if (trimmed.isEmpty()) {
            return columns;
        }
        for (String part : trimmed.split("\\s{2,}")) {
            columns.add(part.trim());
        }
        return columns;
    }

    /** 从「键 : 值」行中拆出键值；不匹配返回 null。 */
    public static String[] splitKeyValue(String line) {
        Matcher matcher = KEY_VALUE.matcher(TextUtil.nvl(line));
        if (!matcher.matches()) {
            return null;
        }
        String key = matcher.group(1);
        if (key.isBlank()) {
            return null;
        }
        return new String[]{key, matcher.group(2)};
    }

    /** 归一化键名：去掉尾部省略号与多余空白。 */
    public static String normalizeKey(String key) {
        String normalized = TextUtil.nvl(key).trim();
        normalized = normalized.replaceAll("[\\s.]+$", "");
        normalized = normalized.replaceAll("\\s{2,}", " ");
        return normalized.trim();
    }

    private static String stripSuffix(String value) {
        int index = value.indexOf('(');
        return index < 0 ? value : value.substring(0, index);
    }

    /** 判断文本是否形如 IPv4 / IPv6 地址。 */
    public static boolean isIpLike(String value) {
        String text = TextUtil.nvl(value).trim();
        if (text.isEmpty()) {
            return false;
        }
        if (text.length() >= 6 && text.indexOf(':') > 0 && text.chars().anyMatch(c -> Character.digit(c, 16) >= 0)) {
            return true;
        }
        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
