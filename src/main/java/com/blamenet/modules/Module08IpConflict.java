package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.Enums;
import com.blamenet.parse.MapAccess;
import com.blamenet.parse.Parsers;
import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块 08：检查 IP 地址冲突。
 * <p>
 * 采集：arp -a（原始证据）+ Get-NetNeighbor（结构化判定）。
 * 判定：同一 MAC 对应多个 IP，或网关 MAC 异常 → Warn（可能 ARP 冲突/欺骗）；
 * 本机 IP 对应的 MAC 与网卡 MAC 不一致 → Fail。
 */
public final class Module08IpConflict extends AbstractCheckModule {

    public Module08IpConflict() {
        super("08", "检查IP地址冲突", FaultLayer.IP_ROUTING, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 arp -a 采集 ARP 表原始输出");
        CmdResult arpRaw = context.cmd(Probes.KEY_ARP_TABLE, Probes.CMDP_ARP_TABLE);

        context.step("执行 Get-NetNeighbor 采集结构化邻居表");
        List<Map<String, Object>> neighbors = context.psJson(Probes.KEY_NEIGHBOR_V4, Probes.NEIGHBOR_V4);

        context.step("比对本机地址与 MAC 映射关系");
        Map<String, List<String>> ipsByMac = new LinkedHashMap<>();
        List<String> ownAddresses = new ArrayList<>();
        for (Map<String, Object> config : context.psJson(Probes.KEY_NET_IP_CONFIGURATION, Probes.NET_IP_CONFIGURATION)) {
            ownAddresses.addAll(MapAccess.csv(config, "IPv4Address"));
        }
        Set<String> ownMacs = new LinkedHashSet<>();
        for (Map<String, Object> adapter : context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER)) {
            String mac = Parsers.normalizeMac(MapAccess.text(adapter, "MacAddress"));
            if (TextUtil.isNotBlank(mac)) {
                ownMacs.add(mac);
            }
        }
        String gateway = firstGateway(context);

        List<String> invalidEntries = new ArrayList<>();
        List<String> conflictIps = new ArrayList<>();
        List<String> mismatchedOwn = new ArrayList<>();
        List<String> suspiciousGateway = new ArrayList<>();

        for (Map<String, Object> neighbor : neighbors) {
            String ip = MapAccess.text(neighbor, "IPAddress");
            String mac = Parsers.normalizeMac(MapAccess.text(neighbor, "LinkLayerAddress"));
            if (TextUtil.isBlank(ip) || TextUtil.isBlank(mac)) {
                continue;
            }
            if (Parsers.macIsSuspicious(mac)) {
                // 全零（未完成解析 / 无效条目）、广播与组播 MAC 都不代表真实的二层设备，
                // 不能据此判定“本机地址被占用”，否则虚拟网卡会造成大量误报。
                invalidEntries.add(ip + " → " + mac);
                continue;
            }
            ipsByMac.computeIfAbsent(mac, key -> new ArrayList<>()).add(ip);
            if (ownAddresses.contains(ip) && !ownMacs.contains(mac)) {
                mismatchedOwn.add(ip + "（邻居表 MAC=" + mac + "，本机网卡 MAC=" + String.join("/", ownMacs) + "）");
            }
            if (ip.equals(gateway) && Parsers.macIsSuspicious(mac)) {
                suspiciousGateway.add(ip + " → " + mac);
            }
        }
        for (Map.Entry<String, List<String>> entry : ipsByMac.entrySet()) {
            if (entry.getValue().size() > 1 && !Parsers.macIsSuspicious(entry.getKey())) {
                conflictIps.add(entry.getKey() + " 同时对应 " + String.join("、", entry.getValue()));
            }
        }

        context.step("判定地址冲突与 ARP 异常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!mismatchedOwn.isEmpty()) {
            status = Status.FAIL;
            summary = "本机 IP 对应的链路层地址与网卡 MAC 不一致：" + String.join("；", mismatchedOwn);
            diagnosis = "本机地址被其他设备占用或存在 ARP 欺骗，会造成间歇性断网与数据被劫持";
            suggestion = "1.在路由器上查看该 IP 的绑定记录，确认是否存在地址冲突；"
                    + "2.为重要设备配置 DHCP 静态绑定（IP-MAC 绑定）；3.扫描局域网内是否有异常设备；"
                    + "4.如为企业网络，请联系网络管理员排查 ARP 欺骗（本工具只读，不会发送任何探测报文）";
        } else if (!conflictIps.isEmpty() || !suspiciousGateway.isEmpty()) {
            status = Status.WARN;
            summary = (!conflictIps.isEmpty() ? "存在同一 MAC 对应多个 IP 的记录：" + String.join("；", conflictIps) : "")
                    + (!suspiciousGateway.isEmpty()
                    ? (conflictIps.isEmpty() ? "" : "；") + "网关 MAC 异常：" + String.join("；", suspiciousGateway) : "");
            diagnosis = "ARP 邻居表中出现异常映射，可能是双网卡桥接/虚拟网卡造成的正常现象，也可能是地址冲突或 ARP 欺骗";
            suggestion = "1.确认是否存在虚拟机网卡、软路由、网卡桥接等正常场景；"
                    + "2.在路由器的 DHCP 客户端列表中核对设备 MAC；"
                    + "3.若为企业网络，请联系管理员检查接入层安全策略（本工具只读）";
        } else if (neighbors.isEmpty()) {
            status = Status.WARN;
            summary = "ARP 邻居表为空，无法进行冲突判定";
            diagnosis = "邻居缓存为空说明近期没有二层通信，通常与链路未连接或刚开机有关";
            suggestion = "确认网卡已连接并产生流量后重新运行本工具";
        } else {
            status = Status.PASS;
            summary = "ARP 邻居表共 " + neighbors.size() + " 条记录，未发现 IP 地址冲突或异常映射"
                    + (TextUtil.isNotBlank(gateway) ? "（网关 " + gateway + " 映射正常）" : "")
                    + (invalidEntries.isEmpty() ? ""
                    : "；已忽略 " + invalidEntries.size() + " 条无效邻居项（全零/广播/组播 MAC）："
                    + String.join("；", invalidEntries));
            diagnosis = "地址与 MAC 映射关系正常，ARP 层未见冲突或欺骗迹象";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("邻居表映射盘点", "BlameNet 汇总：Get-NetNeighbor 只读读取",
                        buildNeighborSummary(neighbors))
                .evidence("arp -a 原始输出", arpRaw)
                .build();
    }

    private static String buildNeighborSummary(List<Map<String, Object>> neighbors) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> neighbor : neighbors) {
            lines.add(MapAccess.text(neighbor, "InterfaceAlias") + " | IP=" + MapAccess.text(neighbor, "IPAddress")
                    + " | MAC=" + MapAccess.text(neighbor, "LinkLayerAddress")
                    + " | 状态=" + MapAccess.text(neighbor, "State")
                    + " | 类型=" + MapAccess.text(neighbor, "Type"));
        }
        return lines.isEmpty() ? "邻居表为空" : String.join("\n", lines);
    }

    private static String firstGateway(DiagnosticContext context) {
        for (Map<String, Object> config : context.psJson(Probes.KEY_NET_IP_CONFIGURATION, Probes.NET_IP_CONFIGURATION)) {
            String gateway = MapAccess.text(config, "DefaultGateway");
            if (Parsers.isValidIpv4(gateway)) {
                return gateway;
            }
        }
        return "";
    }
}
