package com.blamenet.core;

import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.model.Summary;
import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则引擎：由各模块结果推导整体判定。
 * <p>
 * 依据设计文档“三、诊断调度与报告”：
 * <ul>
 *     <li>总判定按故障分层归属：01-04 硬件/驱动、06-09 IP/路由、10-12 链路、14-17 DNS/传输、
 *         18-20 安全/代理、21-25 驱动/系统服务、26-28 深度数据包、29-31 外部验证；</li>
 *     <li>组合规则用于给出更有指向性的根因推断（DHCP 失败、驱动丢包、DNS 与 TCP 的分叉等）。</li>
 * </ul>
 * 所有结论均由本次真实采集结果推导，不包含任何预置判定。
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    /** 生成摘要。{@code manualTaskCount} 为 ManualValidationList 的实际条目数。 */
    public static Summary summarize(List<ModuleResult> results, boolean admin, int manualTaskCount) {
        Summary summary = new Summary();
        List<String> failing = new ArrayList<>();
        List<String> warning = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        List<String> info = new ArrayList<>();

        for (ModuleResult result : results) {
            Status status = Status.fromSource(result.status);
            switch (status) {
                case PASS -> summary.passCount++;
                case WARN -> {
                    summary.warnCount++;
                    warning.add(label(result));
                }
                case FAIL -> {
                    summary.failCount++;
                    failing.add(label(result));
                }
                case MANUAL -> {
                    summary.manualCount++;
                    manual.add(label(result));
                }
                case INFO -> {
                    summary.infoCount++;
                    info.add(label(result));
                }
            }
        }
        summary.totalModuleCount = results.size();
        summary.failingModules = failing;
        summary.warningModules = warning;
        summary.manualModules = manual;
        summary.infoModules = info;
        summary.manualValidationCount = manualTaskCount;
        summary.faultLayers = faultLayers(results);
        summary.riskLevel = riskLevel(results).sourceValue();
        summary.globalDiagnosis = globalDiagnosis(results, admin, summary, manualTaskCount);
        return summary;
    }

    /** 失败模块所归属的故障层（按设计文档的区段规则）。 */
    public static List<String> faultLayers(List<ModuleResult> results) {
        Set<String> layers = new LinkedHashSet<>();
        for (ModuleResult result : results) {
            if (Status.fromSource(result.status) != Status.FAIL) {
                continue;
            }
            for (FaultLayer layer : FaultLayer.values()) {
                if (layer.attributesFailure(result.moduleId)) {
                    layers.add(layer.chinese());
                }
            }
        }
        return new ArrayList<>(layers);
    }

    /** 风险等级判定。 */
    public static RiskLevel riskLevel(List<ModuleResult> results) {
        boolean criticalLayerFail = false;
        boolean anyFail = false;
        boolean anyWarn = false;
        for (ModuleResult result : results) {
            Status status = Status.fromSource(result.status);
            if (status == Status.WARN) {
                anyWarn = true;
            }
            if (status != Status.FAIL) {
                continue;
            }
            anyFail = true;
            for (FaultLayer layer : new FaultLayer[]{
                    FaultLayer.HARDWARE_DRIVER, FaultLayer.IP_ROUTING, FaultLayer.LINK, FaultLayer.DNS_TRANSPORT}) {
                if (layer.attributesFailure(result.moduleId)) {
                    criticalLayerFail = true;
                }
            }
        }
        if (criticalLayerFail) {
            return RiskLevel.CRITICAL;
        }
        if (anyFail) {
            return RiskLevel.HIGH;
        }
        if (anyWarn) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /** 全局诊断文本。 */
    public static String globalDiagnosis(List<ModuleResult> results, boolean admin, Summary summary,
            int manualTaskCount) {
        List<String> sentences = new ArrayList<>();

        if (summary.failCount == 0 && summary.warnCount == 0) {
            sentences.add("本次诊断的 " + summary.totalModuleCount + " 个模块未发现失败项与警告项，"
                    + "本机网络链路、IP 配置、路由、DNS 与端口可达性均处于可用状态");
        } else {
            if (summary.failCount > 0) {
                sentences.add("共发现 " + summary.failCount + " 项失败、" + summary.warnCount + " 项警告");
            } else {
                sentences.add("未发现失败项，存在 " + summary.warnCount + " 项警告，属于潜在隐患而非当前故障");
            }
            if (!summary.faultLayers.isEmpty()) {
                sentences.add("失败项集中在：" + String.join("、", summary.faultLayers));
            }
        }

        for (String rule : applyCombinationRules(results)) {
            sentences.add(rule);
        }

        if (manualTaskCount > 0) {
            // 措辞严格对齐 ManualValidationList：条目数取清单实际长度，
            // 模块编号取“声明了外部验证任务”的模块（含状态为 Warn 的对比测试类模块）。
            sentences.add("外部验证清单共 " + manualTaskCount + " 项（来源模块 "
                    + String.join("、", manualTaskModuleIds(results))
                    + "），本工具无法在当前系统内自动执行，需要用户按操作说明自行完成，完成后可回填工具进行二次分析");
        } else if (summary.manualCount > 0) {
            sentences.add("存在 " + summary.manualCount + " 个手动状态模块（模块 "
                    + String.join("、", moduleIdsOf(results, Status.MANUAL))
                    + "），本工具无法在当前系统内自动执行，需要用户按操作说明自行完成，完成后可回填工具进行二次分析");
        }

        if (!admin) {
            sentences.add("当前进程未以管理员身份运行，sfc / DISM / pktmon 等只读深度检查无法执行，"
                    + "相关模块结果已如实标注，建议以管理员身份重新运行以获得完整结论");
        }

        String text = String.join("；", sentences);
        if (!text.endsWith("。")) {
            text = text + "。";
        }
        return text;
    }

    /**
     * 组合规则（对应设计文档中的规则引擎示例，并结合故障树扩充）。
     * 只有当前置模块真实失败时才输出对应推断。
     */
    private static List<String> applyCombinationRules(List<ModuleResult> results) {
        List<String> rules = new ArrayList<>();
        StatusHolder holder = new StatusHolder(results);

        if (holder.isFail("06") && holder.isFail("07")) {
            rules.add("DHCP 获取失败：网卡未取得有效租约并出现 APIPA 链路本地地址，疑似路由器 DHCP 服务未响应或物理链路异常");
        } else if (holder.isFail("06")) {
            rules.add("IP 配置异常：IPv4 地址、默认网关或 DNS 配置不完整");
        }
        if (holder.isFail("12") && holder.hasDiscardedPackets()) {
            rules.add("网卡存在丢弃数据包，同时网关不可达，指向网卡驱动或硬件链路问题");
        } else if (holder.isFail("12")) {
            rules.add("到默认网关的连通性失败，故障位于本机到路由器的这一段链路");
        }
        if (holder.isFail("14") && holder.isTcpReachable()) {
            rules.add("DNS 解析失败但 TCP 端口可达，故障明确位于域名解析环节而非网络链路");
        }
        if (holder.isFail("09")) {
            rules.add("路由表缺少 0.0.0.0/0 默认路由，出网流量无出口");
        }
        if (holder.isFail("01") || holder.isFail("02")) {
            rules.add("网卡未被系统正常识别或设备存在问题代码，属于硬件/驱动层故障");
        }
        if (holder.isFail("10") || holder.isFail("11")) {
            rules.add("本机 TCP/IP 协议栈或网卡地址绑定异常");
        }
        if (holder.isFail("27")) {
            rules.add("网关的 ARP/ND 邻居发现失败，二层不可达");
        }
        if (holder.isFail("28")) {
            rules.add("路径 MTU 不匹配：小包可通、大包被丢弃，典型表现为能 ping 通但打不开网页");
        }
        if (holder.isWarn("22") || holder.isWarn("23")) {
            rules.add("网卡或电源计划中的节能设置处于开启状态，可能造成间歇性断连");
        }
        if (holder.isWarn("21")) {
            rules.add("网卡驱动版本较旧，建议更新至厂商最新驱动");
        }
        if (holder.isWarn("25")) {
            rules.add("系统文件完整性存在冲突，属于系统层隐患");
        }
        if (holder.isWarn("08")) {
            rules.add("ARP 邻居表中存在异常映射，需注意 IP 地址冲突或 ARP 欺骗");
        }
        if (holder.isWarn("18") || holder.isWarn("19") || holder.isWarn("20")) {
            rules.add("防火墙出站规则、代理配置或 hosts 文件中存在可能阻断访问的设置");
        }
        if (holder.isFail("26")) {
            rules.add("Pktmon 抓包归因显示网卡侧存在丢包");
        }
        if (holder.isFail("17")) {
            rules.add("远程 TCP 端口不可达，安全软件拦截或链路中断均可能造成该现象");
        }
        return rules;
    }

    private static List<String> moduleIdsOf(List<ModuleResult> results, Status status) {
        List<String> ids = new ArrayList<>();
        for (ModuleResult result : results) {
            if (Status.fromSource(result.status) == status) {
                ids.add(result.moduleId);
            }
        }
        return ids;
    }

    /** 声明了外部验证任务的模块编号，与 ManualValidationList 的条目一一对应。 */
    private static List<String> manualTaskModuleIds(List<ModuleResult> results) {
        List<String> ids = new ArrayList<>();
        for (ModuleResult result : results) {
            if (result.manualTask != null) {
                ids.add(result.moduleId);
            }
        }
        return ids;
    }

    private static String label(ModuleResult result) {
        return result.moduleId + " " + result.moduleName;
    }

    /** 供规则使用的状态查询包装。 */
    private static final class StatusHolder {

        private final List<ModuleResult> results;

        StatusHolder(List<ModuleResult> results) {
            this.results = results;
        }

        private ModuleResult find(String moduleId) {
            for (ModuleResult result : results) {
                if (moduleId.equals(result.moduleId)) {
                    return result;
                }
            }
            return null;
        }

        boolean isFail(String moduleId) {
            ModuleResult result = find(moduleId);
            return result != null && Status.fromSource(result.status) == Status.FAIL;
        }

        boolean isWarn(String moduleId) {
            ModuleResult result = find(moduleId);
            return result != null && Status.fromSource(result.status) == Status.WARN;
        }

        /** 模块 04（网卡丢弃计数器）中是否发现真实丢弃计数。 */
        boolean hasDiscardedPackets() {
            ModuleResult result = find("04");
            if (result == null || result.evidences == null) {
                return false;
            }
            String text = TextUtil.nvl(result.summary) + " " + TextUtil.nvl(result.diagnosis);
            return text.contains("丢弃") && (text.contains(">0") || text.contains("增长") || text.contains("非零"));
        }

        /** 模块 17（远程 TCP 端口）是否 TCP 可达。 */
        boolean isTcpReachable() {
            ModuleResult result = find("17");
            if (result == null) {
                return false;
            }
            String text = TextUtil.nvl(result.summary) + " " + TextUtil.nvl(result.diagnosis);
            return text.contains("TCP") && (text.contains("可达") || text.contains("成功"));
        }
    }
}
