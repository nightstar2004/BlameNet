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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模块 27：检查 ARP/ND 邻居发现失败。
 * <p>
 * 采集：Get-NetNeighbor -AddressFamily IPv4 与 IPv6。
 * 判定（严格按计划文档）：网关邻居状态 Unreachable/Incomplete → Fail；存在大量 Stale → Warn。
 * <p>
 * 非网关的未完成/不可达条目不足以证明本机故障（对端设备下线属常态），因此不单独判定，
 * 只作为诊断信息与证据保留。对于全零 / 广播 / 组播 MAC 的条目（代表邻居解析未完成、
 * 并非真实二层设备），与模块 08 采用同一口径忽略，且按 IP 去重——同一地址会在多个接口上
 * 重复出现，计入判定会造成严重误报。
 */
public final class Module27NeighborDiscovery extends AbstractCheckModule {

    /** Stale 邻居数量的告警阈值。 */
    private static final int STALE_THRESHOLD = 8;

    public Module27NeighborDiscovery( ) {
        super("27", "检查ARP/ND邻居发现失败", FaultLayer.DEEP_PACKET, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetNeighbor 采集 IPv4 邻居表");
        List<Map<String, Object>> v4 = context.psJson(Probes.KEY_NEIGHBOR_V4, Probes.NEIGHBOR_V4);
        CmdResult v4Raw = context.ps(Probes.KEY_NEIGHBOR_V4, Probes.NEIGHBOR_V4);

        context.step("执行 Get-NetNeighbor 采集 IPv6 邻居表");
        List<Map<String, Object>> v6 = context.psJson(Probes.KEY_NEIGHBOR_V6, Probes.NEIGHBOR_V6);
        CmdResult v6Raw = context.ps(Probes.KEY_NEIGHBOR_V6, Probes.NEIGHBOR_V6);

        context.step("筛查网关邻居状态与 Stale 数量");
        String gateway = NetFacts.defaultGateway(context);
        boolean gatewayKnown = TextUtil.isNotBlank(gateway);
        boolean gatewayInTable = false;
        List<String> gatewayProblems = new ArrayList<>();
        List<String> invalidEntries = new ArrayList<>();
        List<String> unreachableEntries = new ArrayList<>();
        Set<String> unreachableIps = new LinkedHashSet<>();
        int staleCount = 0;
        int validCount = 0;

        for (Map<String, Object> neighbor : v4) {
            String ip = MapAccess.text(neighbor, "IPAddress");
            String state = Enums.neighborState(MapAccess.text(neighbor, "State"));
            String mac = MapAccess.text(neighbor, "LinkLayerAddress");
            String macText = TextUtil.isBlank(mac) ? "无" : mac;
            String lowerState = state.toLowerCase(Locale.ROOT);
            boolean unreachable = "unreachable".equals(lowerState) || "incomplete".equals(lowerState);
            boolean gatewayEntry = gatewayKnown && gateway.equals(ip);
            if (gatewayEntry) {
                gatewayInTable = true;
                if (unreachable) {
                    // 网关判定优先于无效项过滤：网关解析失败正是本模块要抓的核心故障，
                    // 不能因为它的 MAC 恰好是全零就被过滤掉。
                    gatewayProblems.add(ip + "（" + state + "，MAC=" + macText + "）");
                    continue;
                }
            }
            if (Parsers.macIsSuspicious(mac)) {
                invalidEntries.add(ip + "（" + state + "，MAC=" + macText + "）");
                continue;
            }
            validCount++;
            if ("stale".equals(lowerState)) {
                staleCount++;
            }
            if (unreachable && unreachableIps.add(ip)) {
                unreachableEntries.add(ip + "（" + state + "，MAC=" + macText + "）");
            }
        }

        context.step("判定二层邻居发现是否正常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (v4.isEmpty()) {
            status = Status.WARN;
            summary = "IPv4 邻居表为空，无法进行邻居发现判定";
            diagnosis = "邻居表为空说明近期没有二层通信，通常与链路未连接、网卡未启用或刚开机有关";
            suggestion = "确认网卡已连接并产生流量后重新运行本工具";
        } else if (!gatewayProblems.isEmpty()) {
            status = Status.FAIL;
            summary = "网关邻居发现失败：" + String.join("；", gatewayProblems)
                    + "（网关 " + gateway + " 在二层不可达）";
            diagnosis = "ARP（IPv4）/ND（IPv6）无法解析网关的 MAC 地址，说明本机与路由器之间的二层链路不通："
                    + "可能是网线/端口故障、VLAN 配置不一致、无线信号中断，或网关地址配置错误";
            suggestion = "1.检查网线两端与端口指示灯，更换端口后重试；2.无线场景确认已连接且信号良好；"
                    + "3.在管理员命令行中手动执行 arp -a 确认网关是否有 MAC 记录"
                    + "（本工具只读，不会执行 arp -d 等任何修改命令）";
        } else if (staleCount >= STALE_THRESHOLD) {
            status = Status.WARN;
            summary = "邻居表中存在大量 Stale 记录（" + staleCount + " 条，超过阈值 " + STALE_THRESHOLD + "）";
            diagnosis = "大量 Stale 记录说明邻居表老化较快或网络内设备频繁上下线，可能伴随广播风暴或链路抖动"
                    + (unreachableEntries.isEmpty() ? ""
                    : "；另有 " + unreachableEntries.size() + " 个有效邻居当前不可达");
            suggestion = "结合模块 04（丢弃计数）与模块 12（网关丢包）判断链路质量；"
                    + "如怀疑广播风暴，请检查局域网内是否存在环路或异常设备";
        } else {
            status = Status.PASS;
            summary = "邻居发现正常：IPv4 邻居 " + v4.size() + " 条（有效 " + validCount + " 条，"
                    + invalidEntries.size() + " 条未完成解析的无效项已忽略）、IPv6 邻居 " + v6.size()
                    + " 条；网关" + (gatewayKnown ? " " + gateway : "（无有效默认网关）")
                    + (gatewayKnown && !gatewayInTable ? " 未出现在邻居表中" : " 的邻居状态可达")
                    + "，Stale 记录 " + staleCount + " 条";
            diagnosis = "本机与网关的二层邻居关系正常，ARP/ND 解析工作正常"
                    + (unreachableEntries.isEmpty() ? ""
                    : "。另有 " + unreachableEntries.size() + " 个持有有效 MAC 的邻居当前不可达（"
                    + String.join("、", first(unreachableEntries, 5))
                    + "），通常是对端设备下线，不构成本机故障");
            suggestion = unreachableEntries.isEmpty()
                    ? "无"
                    : "若不可达地址中包含你需要访问的常用设备，请确认其在线状态；历史残留记录会由系统自动老化";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("邻居表盘点", "BlameNet 汇总：Get-NetNeighbor（IPv4 / IPv6）",
                        "网关：" + (gatewayKnown ? gateway : "无有效默认网关")
                                + "\nStale 记录数：" + staleCount
                                + "\n有效邻居条目：" + validCount + " 条"
                                + "\n已忽略的无效项（全零/广播/组播 MAC，可能与多接口重复）："
                                + (invalidEntries.isEmpty() ? "无" : "\n" + String.join("\n", invalidEntries))
                                + "\n未完成/不可达的有效记录（按 IP 去重）："
                                + (unreachableEntries.isEmpty() ? "无" : "\n" + String.join("\n", unreachableEntries))
                                + "\n\nIPv4 邻居明细：\n" + summarize(v4)
                                + "\n\nIPv6 邻居明细：\n" + summarize(v6))
                .evidence("Get-NetNeighbor IPv4 原始输出", v4Raw)
                .evidence("Get-NetNeighbor IPv6 原始输出", v6Raw)
                .build();
    }

    private static String summarize(List<Map<String, Object>> rows) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add(MapAccess.text(row, "InterfaceAlias") + " | " + MapAccess.text(row, "IPAddress")
                    + " | " + MapAccess.text(row, "LinkLayerAddress") + " | " + MapAccess.text(row, "State")
                    + " | " + MapAccess.text(row, "Type"));
        }
        return lines.isEmpty() ? "无记录" : String.join("\n", lines);
    }

    private static List<String> first(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }
}
