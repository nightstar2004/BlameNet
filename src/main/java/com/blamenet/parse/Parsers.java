package com.blamenet.parse;

import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 领域解析器：把真实命令输出解析为结构化对象。
 * <p>
 * 所有解析都基于运行时真实输出，本类中不包含任何预置的“样本结果”。
 */
public final class Parsers {

    private Parsers() {
    }

    // ==================================================================================
    // ping
    // ==================================================================================

    /** 一次 ping 的统计结果。 */
    public record PingStats(int sent, int received, int lost, double lossPercent,
                            double minMs, double avgMs, double maxMs,
                            boolean anyReply, boolean fragmentedNeeded, boolean timedOut, boolean unreachable) {

        /** 是否可达（收到过应答）。 */
        public boolean reachable() {
            return anyReply && received > 0;
        }

        public String summaryText() {
            if (sent == 0 && !anyReply && !fragmentedNeeded) {
                return "未采集到 ping 统计信息";
            }
            StringBuilder sb = new StringBuilder();
            if (sent > 0) {
                sb.append("发送 ").append(sent).append(" 个，接收 ").append(received)
                        .append(" 个，丢失 ").append(lost).append(" 个（").append(fmt(lossPercent)).append("%）");
            }
            if (anyReply) {
                sb.append("；延迟 最短/平均/最长 = ").append(fmt(minMs)).append('/')
                        .append(fmt(avgMs)).append('/').append(fmt(maxMs)).append(" ms");
            }
            if (fragmentedNeeded) {
                sb.append("；需要拆分数据包但已设置 DF 标志");
            }
            if (timedOut) {
                sb.append("；出现请求超时");
            }
            if (unreachable) {
                sb.append("；出现无法访问目标主机");
            }
            return sb.toString();
        }

        private static String fmt(double value) {
            if (value == Math.rint(value)) {
                return String.valueOf((long) value);
            }
            return String.format(Locale.ROOT, "%.1f", value);
        }
    }

    /** 解析 ping 输出（支持中文与英文系统）。 */
    public static PingStats parsePing(String raw) {
        String text = TextUtil.normalize(raw);
        int sent = (int) firstNumber(text, "已发送\\s*=\\s*(\\d+)", "Sent\\s*=\\s*(\\d+)");
        int received = (int) firstNumber(text, "已接收\\s*=\\s*(\\d+)", "Received\\s*=\\s*(\\d+)");
        int lost = (int) firstNumber(text, "丢失\\s*=\\s*(\\d+)", "Lost\\s*=\\s*(\\d+)");
        double lossPercent = firstNumber(text, "\\((\\d+)\\s*%\\s*(?:丢失|loss)", "(\\d+)\\s*%\\s*(?:丢失|loss)");
        double minMs = firstNumber(text, "最短\\s*=\\s*(\\d+)ms", "Minimum\\s*=\\s*(\\d+)ms");
        double avgMs = firstNumber(text, "平均\\s*=\\s*(\\d+)ms", "Average\\s*=\\s*(\\d+)ms");
        double maxMs = firstNumber(text, "最长\\s*=\\s*(\\d+)ms", "Maximum\\s*=\\s*(\\d+)ms");

        int replyCount = countMatches(text, "(?:字节=|bytes=)");
        boolean anyReply = replyCount > 0 || received > 0;
        boolean fragmentedNeeded = text.contains("需要拆分数据包但设置 DF")
                || text.contains("Packet needs to be fragmented but DF set");
        boolean timedOut = text.contains("请求超时") || text.contains("Request timed out")
                || text.contains("超时") && replyCount == 0 && !fragmentedNeeded;
        boolean unreachable = text.contains("无法访问目标主机") || text.contains("Destination host unreachable")
                || text.contains("一般故障") || text.contains("General failure");

        if (sent == 0 && lost == 0 && replyCount == 0 && !fragmentedNeeded && !timedOut && !unreachable) {
            lost = 0;
        }
        return new PingStats(sent, received, lost, lossPercent, minMs, avgMs, maxMs,
                anyReply, fragmentedNeeded, timedOut, unreachable);
    }

    // ==================================================================================
    // ipconfig /all
    // ==================================================================================

    /** ipconfig 中单个适配器的 IPv4 配置。 */
    public record Ipv4Config(String adapterName, String description, String mac,
                             List<String> ipv4, List<String> masks, List<String> gateways,
                             List<String> dns, String dhcpEnabled, String dhcpServer,
                             String mediaState) {

        public boolean hasAnyIpv4() {
            return !ipv4.isEmpty();
        }

        /** 是否获取到 APIPA（169.254.x.x，DHCP 失败后的链路本地地址）。 */
        public boolean hasApipa() {
            return ipv4.stream().anyMatch(Parsers::isApipa);
        }

        /** 是否存在 0.0.0.0 地址。 */
        public boolean hasZeroAddress() {
            return ipv4.stream().anyMatch(Parsers::isZeroAddress);
        }

        public boolean hasGateway() {
            return gateways.stream().anyMatch(Parsers::isValidIpv4);
        }
    }

    /** 解析 ipconfig /all 输出。 */
    public static List<Ipv4Config> parseIpconfig(String raw) {
        List<Ipv4Config> configs = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, String>>> section : TextTable.parseSections(raw)) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> ipv4 = new ArrayList<>();
            List<String> masks = new ArrayList<>();
            List<String> gateways = new ArrayList<>();
            List<String> dns = new ArrayList<>();
            for (Map.Entry<String, String> entry : section.getValue()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (matchesAny(key, "Description", "描述")) {
                    values.put("description", value);
                } else if (matchesAny(key, "Physical Address", "物理地址")) {
                    values.put("mac", value);
                } else if (matchesAny(key, "DHCP Enabled", "已启用 DHCP")) {
                    values.put("dhcpEnabled", value);
                } else if (matchesAny(key, "DHCP Server", "DHCP 服务器")) {
                    values.put("dhcpServer", value);
                } else if (matchesAny(key, "Media State", "媒体状态")) {
                    values.put("mediaState", value);
                } else if (matchesAny(key, "Default Gateway", "默认网关")) {
                    addAll(gateways, value);
                } else if (matchesAny(key, "DNS Servers", "DNS 服务器")) {
                    addAll(dns, value);
                } else if (matchesAny(key, "Subnet Mask", "子网掩码")) {
                    addAll(masks, value);
                } else if (key.contains("IPv4") || key.contains("IP Address") || key.contains("IP 地址")) {
                    addAll(ipv4, value);
                }
            }
            configs.add(new Ipv4Config(section.getKey(),
                    values.getOrDefault("description", ""),
                    values.getOrDefault("mac", ""),
                    cleanAddresses(ipv4),
                    cleanAddresses(masks),
                    cleanAddresses(gateways),
                    cleanAddresses(dns),
                    values.getOrDefault("dhcpEnabled", ""),
                    values.getOrDefault("dhcpServer", ""),
                    values.getOrDefault("mediaState", "")));
        }
        return configs;
    }

    // ==================================================================================
    // netsh wlan show interfaces
    // ==================================================================================

    /** 无线接口信息。 */
    public record WlanInterface(String name, String description, String mac, String state, String ssid, String bssid,
                                String radioType, String authentication, String cipher, String channel,
                                String receiveRate, String transmitRate, String signal, String profile) {

        /** 信号百分比，解析失败返回 -1。 */
        public int signalPercent() {
            String value = TextUtil.nvl(signal).replace("%", "").trim();
            if (value.isEmpty()) {
                return -1;
            }
            return (int) TextUtil.parseLong(value, -1);
        }

        /** 是否处于已连接状态。 */
        public boolean connected() {
            String value = TextUtil.nvl(state).toLowerCase(Locale.ROOT);
            return value.contains("已连接") || value.contains("connected") && !value.contains("disconnected");
        }

        /** 是否处于已断开状态。 */
        public boolean disconnected() {
            String value = TextUtil.nvl(state).toLowerCase(Locale.ROOT);
            return value.contains("断开") || value.contains("disconnected");
        }
    }

    /** 解析 netsh wlan show interfaces 输出。 */
    public static List<WlanInterface> parseNetshWlan(String raw) {
        List<WlanInterface> interfaces = new ArrayList<>();
        for (Map<String, String> block : TextTable.parseListBlocks(raw)) {
            String name = find(block, "名称", "Name");
            if (TextUtil.isBlank(name) && TextUtil.isBlank(find(block, "SSID"))) {
                continue;
            }
            interfaces.add(new WlanInterface(
                    name,
                    find(block, "描述", "Description"),
                    find(block, "物理地址", "Physical address"),
                    find(block, "状态", "State"),
                    find(block, "SSID"),
                    find(block, "BSSID"),
                    find(block, "无线电类型", "Radio type"),
                    find(block, "身份验证", "Authentication"),
                    find(block, "密码", "Cipher"),
                    find(block, "信道", "Channel"),
                    findPrefix(block, "接收速率", "Receive rate"),
                    findPrefix(block, "传输速率", "Transmit rate"),
                    find(block, "信号", "Signal"),
                    find(block, "配置文件", "Profile")));
        }
        return interfaces;
    }

    // ==================================================================================
    // powercfg
    // ==================================================================================

    /** 单个电源设置项。 */
    public record PowerSetting(String settingName, String settingGuid, int acIndex, int dcIndex,
                               Map<Integer, String> indexNames) {

        /** 当前交流电源下的设置名称，未知时返回索引文本。 */
        public String acValueName() {
            String name = indexNames.get(acIndex);
            return name != null ? name : ("索引 " + acIndex);
        }
    }

    /** 解析 powercfg /query 输出。 */
    public static List<PowerSetting> parsePowercfgQuery(String raw) {
        List<PowerSetting> settings = new ArrayList<>();
        String settingName = null;
        String settingGuid = null;
        Map<Integer, String> indexNames = new LinkedHashMap<>();
        int acIndex = -1;
        int dcIndex = -1;
        Integer lastIndex = null;

        for (String line : TextUtil.normalize(raw).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (startsWithAny(trimmed, "电源设置 GUID", "Power Setting GUID")) {
                if (settingGuid != null) {
                    settings.add(new PowerSetting(settingName, settingGuid, acIndex, dcIndex, indexNames));
                }
                settingGuid = firstToken(afterColon(trimmed));
                settingName = parenthesized(trimmed);
                indexNames = new LinkedHashMap<>();
                acIndex = -1;
                dcIndex = -1;
                lastIndex = null;
            } else if (startsWithAny(trimmed, "可能的设置索引", "Possible Setting Index")) {
                lastIndex = (int) TextUtil.parseLong(afterColon(trimmed), -1);
            } else if (startsWithAny(trimmed, "可能的设置友好名称", "Possible Setting Friendly Name")) {
                if (lastIndex != null) {
                    indexNames.put(lastIndex, afterColon(trimmed).trim());
                }
            } else if (startsWithAny(trimmed, "当前交流电源设置索引", "Current AC Power Setting Index")) {
                acIndex = parseIndex(afterColon(trimmed));
            } else if (startsWithAny(trimmed, "当前直流电源设置索引", "Current DC Power Setting Index")) {
                dcIndex = parseIndex(afterColon(trimmed));
            }
        }
        if (settingGuid != null) {
            settings.add(new PowerSetting(settingName, settingGuid, acIndex, dcIndex, indexNames));
        }
        return settings;
    }

    /** 解析 "0x00000001" 形式的设置索引。 */
    public static int parseIndex(String value) {
        String text = TextUtil.nvl(value).trim();
        if (text.isEmpty()) {
            return -1;
        }
        try {
            if (text.toLowerCase(Locale.ROOT).startsWith("0x")) {
                return Integer.parseInt(text.substring(2).trim(), 16);
            }
            return (int) TextUtil.parseLong(text, -1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==================================================================================
    // Test-NetConnection
    // ==================================================================================

    /** TCP 端口可达性测试结果。 */
    public record TcpTest(String target, int port, String remoteAddress, String sourceAddress,
                          boolean pingSucceeded, boolean tcpSucceeded, String rtt, boolean parsed) {
    }

    /** 解析 Test-NetConnection 输出。 */
    public static TcpTest parseTestNetConnection(String raw, String target, int port) {
        Map<String, String> values = TextTable.parseKeyValues(raw);
        String remoteAddress = find(values, "RemoteAddress");
        String sourceAddress = find(values, "SourceAddress");
        String rtt = findPrefix(values, "PingReplyDetails");
        boolean pingSucceeded = "true".equalsIgnoreCase(find(values, "PingSucceeded"));
        boolean tcpSucceeded = "true".equalsIgnoreCase(find(values, "TcpTestSucceeded"));
        boolean parsed = values.containsKey("TcpTestSucceeded") || values.containsKey("PingSucceeded");
        return new TcpTest(target, port, remoteAddress, sourceAddress, pingSucceeded, tcpSucceeded, rtt, parsed);
    }

    // ==================================================================================
    // 地址工具
    // ==================================================================================

    /** APIPA 链路本地地址（169.254.x.x）。 */
    public static boolean isApipa(String ip) {
        return TextUtil.nvl(ip).trim().startsWith("169.254.");
    }

    /** 0.0.0.0。 */
    public static boolean isZeroAddress(String ip) {
        return "0.0.0.0".equals(TextUtil.nvl(ip).trim());
    }

    /** 是否为合法的 IPv4 地址。 */
    public static boolean isValidIpv4(String ip) {
        String text = TextUtil.nvl(ip).trim();
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

    /** 取第一个合法 IPv4。 */
    public static String firstIpv4(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (isValidIpv4(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /** 归一化 MAC 地址：大写，'-' 分隔。 */
    public static String normalizeMac(String mac) {
        String text = TextUtil.nvl(mac).trim().replace(':', '-').toUpperCase(Locale.ROOT);
        return text;
    }

    /** MAC 是否异常（全零、广播或组播位被置位）。 */
    public static boolean macIsSuspicious(String mac) {
        String text = normalizeMac(mac);
        if (text.isEmpty()) {
            return true;
        }
        if (text.startsWith("00-00-00-00-00-00")) {
            return true;
        }
        if (text.startsWith("FF-FF-FF-FF-FF-FF")) {
            return true;
        }
        String firstOctet = text.split("-")[0];
        try {
            int value = Integer.parseInt(firstOctet, 16);
            return (value & 0x01) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ==================================================================================
    // 内部工具
    // ==================================================================================

    private static void addAll(List<String> target, String value) {
        for (String part : TextUtil.nvl(value).split("[\\s,]+")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
    }

    /** 去掉 (首选)/(Preferred) 等后缀。 */
    private static List<String> cleanAddresses(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String text = value.trim();
            int index = text.indexOf('(');
            if (index > 0) {
                text = text.substring(0, index).trim();
            }
            if (!text.isEmpty() && !cleaned.contains(text)) {
                cleaned.add(text);
            }
        }
        return cleaned;
    }

    private static boolean matchesAny(String key, String... candidates) {
        String normalized = TextTable.normalizeKey(key).toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.equals(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String find(Map<String, String> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && TextUtil.isNotBlank(map.get(key))) {
                return map.get(key).trim();
            }
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && TextUtil.isNotBlank(entry.getValue())) {
                    return entry.getValue().trim();
                }
            }
        }
        return "";
    }

    private static String findPrefix(Map<String, String> map, String... prefixes) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            for (String prefix : prefixes) {
                if (entry.getKey().startsWith(prefix) && TextUtil.isNotBlank(entry.getValue())) {
                    return entry.getValue().trim();
                }
            }
        }
        return "";
    }

    private static boolean startsWithAny(String line, String... prefixes) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String afterColon(String line) {
        int index = line.indexOf(':');
        if (index < 0) {
            index = line.indexOf('：');
        }
        return index < 0 ? "" : line.substring(index + 1).trim();
    }

    /** 取出形如 "0d9b5c1c-1234-... (节能模式)" 中的第一个 GUID。 */
    private static String firstToken(String value) {
        String text = TextUtil.nvl(value).trim();
        int space = text.indexOf(' ');
        int paren = text.indexOf('(');
        int end = text.length();
        if (space > 0) {
            end = Math.min(end, space);
        }
        if (paren > 0) {
            end = Math.min(end, paren);
        }
        return text.substring(0, end).trim();
    }

    private static String parenthesized(String line) {
        int start = line.indexOf('(');
        int end = line.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return "";
        }
        return line.substring(start + 1, end).trim();
    }

    private static double firstNumber(String text, String... patterns) {
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            if (matcher.find()) {
                return TextUtil.parseDouble(matcher.group(1), 0);
            }
        }
        return 0;
    }

    private static int countMatches(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
