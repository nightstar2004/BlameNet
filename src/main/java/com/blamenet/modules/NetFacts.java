package com.blamenet.modules;

import com.blamenet.collect.Probes;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.parse.MapAccess;
import com.blamenet.parse.Parsers;
import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块共享的网络事实读取。
 * <p>
 * 全部来自上下文中已缓存的真实采集结果，因此不同模块看到的同一事实必定一致。
 */
final class NetFacts {

    private NetFacts() {
    }

    /** Get-NetIPConfiguration 结果。 */
    static List<Map<String, Object>> ipConfigurations(DiagnosticContext context) {
        return context.psJson(Probes.KEY_NET_IP_CONFIGURATION, Probes.NET_IP_CONFIGURATION);
    }

    /**
     * 主用网卡：真正承担本机上网流量的那张网卡。
     * <p>
     * 选取顺序：承载默认网关的接口 → 首个持有合法非 APIPA 地址的接口 → 首个接口。
     * 判定“IP 配置 / DHCP 租约”类结论时必须以主用网卡为对象，否则 VPN、Hyper-V、
     * VMware 等虚拟网卡自带的 169.254 链路本地地址会造成整机故障误报。
     */
    record PrimaryInterface(String alias, String description, String ipv4, String gateway) {

        boolean hasGateway() {
            return Parsers.isValidIpv4(gateway);
        }

        boolean apipa() {
            return ipv4 != null && Parsers.isApipa(ipv4);
        }

        boolean zero() {
            return ipv4 != null && Parsers.isZeroAddress(ipv4);
        }

        boolean valid() {
            return ipv4 != null && Parsers.isValidIpv4(ipv4) && !apipa() && !zero();
        }

        String display() {
            StringBuilder sb = new StringBuilder(TextUtil.nvl(alias));
            if (TextUtil.isNotBlank(description)) {
                sb.append("（").append(description).append("）");
            }
            return sb.toString();
        }
    }

    /** 解析主用网卡（无任何接口时返回 null）。 */
    static PrimaryInterface primaryInterface(DiagnosticContext context) {
        List<Map<String, Object>> configs = ipConfigurations(context);
        Map<String, Object> chosen = null;
        for (Map<String, Object> config : configs) {
            if (Parsers.isValidIpv4(MapAccess.text(config, "DefaultGateway"))) {
                chosen = config;
                break;
            }
        }
        if (chosen == null) {
            for (Map<String, Object> config : configs) {
                String ipv4 = Parsers.firstIpv4(MapAccess.csv(config, "IPv4Address"));
                if (ipv4 != null && Parsers.isValidIpv4(ipv4)
                        && !Parsers.isApipa(ipv4) && !Parsers.isZeroAddress(ipv4)) {
                    chosen = config;
                    break;
                }
            }
        }
        if (chosen == null && !configs.isEmpty()) {
            chosen = configs.get(0);
        }
        if (chosen == null) {
            return null;
        }
        return new PrimaryInterface(
                MapAccess.text(chosen, "InterfaceAlias"),
                MapAccess.text(chosen, "InterfaceDescription"),
                Parsers.firstIpv4(MapAccess.csv(chosen, "IPv4Address")),
                MapAccess.text(chosen, "DefaultGateway"));
    }

    /**
     * 判断某个网卡名称（接口别名或适配器描述）是否为主用网卡。
     * <p>
     * Win32_NetworkAdapterConfiguration 使用 Description，Get-NetIPConfiguration 使用
     * InterfaceAlias / InterfaceDescription，这里做双向包含匹配，兼容两种命名。
     */
    static boolean isPrimary(PrimaryInterface primary, String name) {
        if (primary == null || TextUtil.isBlank(name)) {
            return false;
        }
        String value = name.trim().toLowerCase();
        for (String candidate : new String[]{primary.alias(), primary.description()}) {
            if (TextUtil.isBlank(candidate)) {
                continue;
            }
            String target = candidate.trim().toLowerCase();
            if (target.equals(value) || target.contains(value) || value.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /** 本机第一个有效 IPv4 地址（含网卡绑定关系）。 */
    static String localIpv4(DiagnosticContext context) {
        for (Map<String, Object> config : ipConfigurations(context)) {
            String ipv4 = Parsers.firstIpv4(MapAccess.csv(config, "IPv4Address"));
            if (ipv4 != null) {
                return ipv4;
            }
        }
        for (Map<String, Object> row : context.psJson(Probes.KEY_IP_ADDRESS_V4, Probes.IP_ADDRESS_V4)) {
            String address = MapAccess.text(row, "IPAddress");
            if (Parsers.isValidIpv4(address)) {
                return address;
            }
        }
        return null;
    }

    /** 本机默认网关。 */
    static String defaultGateway(DiagnosticContext context) {
        for (Map<String, Object> config : ipConfigurations(context)) {
            String gateway = MapAccess.text(config, "DefaultGateway");
            if (Parsers.isValidIpv4(gateway)) {
                return gateway;
            }
        }
        return null;
    }

    /** 承载默认网关的接口别名。 */
    static String gatewayInterface(DiagnosticContext context) {
        for (Map<String, Object> config : ipConfigurations(context)) {
            String gateway = MapAccess.text(config, "DefaultGateway");
            if (Parsers.isValidIpv4(gateway)) {
                return MapAccess.text(config, "InterfaceAlias");
            }
        }
        return "";
    }

    /** 各接口的 DNS 服务器。 */
    static Map<String, List<String>> dnsServers(DiagnosticContext context) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : context.psJson(Probes.KEY_DNS_CLIENT_SERVER, Probes.DNS_CLIENT_SERVER)) {
            List<String> servers = MapAccess.stringList(row, "ServerAddresses");
            if (!servers.isEmpty()) {
                result.put(MapAccess.text(row, "InterfaceAlias"), servers);
            }
        }
        return result;
    }

    /** 判断某块适配器是否为无线网卡（虚拟网卡不计入）。 */
    static boolean isWireless(Map<String, Object> adapter) {
        if (MapAccess.bool(adapter, "Virtual")) {
            return false;
        }
        String mediaType = MapAccess.text(adapter, "MediaType").toLowerCase(Locale.ROOT);
        String physical = MapAccess.text(adapter, "PhysicalMediaType").toLowerCase(Locale.ROOT);
        return mediaType.contains("802.11") || physical.contains("802.11");
    }

    /**
     * 本机无线网卡数量。
     * <p>
     * 用于判断“依赖无线的服务是否适用于本机”：没有无线网卡的台式机上，
     * WlanSvc 未运行属于正常现象，不应判为故障。
     */
    static int wirelessAdapterCount(DiagnosticContext context) {
        int count = 0;
        for (Map<String, Object> adapter : context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER)) {
            if (isWireless(adapter)) {
                count++;
            }
        }
        return count;
    }

    /** 网卡名称列表（用于展示）。 */
    static List<String> adapterNames(DiagnosticContext context) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> adapter : context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER)) {
            String name = MapAccess.text(adapter, "Name");
            if (TextUtil.isNotBlank(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
