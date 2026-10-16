package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.MapAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 18：检查本机防火墙规则。
 * <p>
 * 采集：Get-NetFirewallProfile + Get-NetFirewallRule -Enabled True -Direction Outbound -Action Block。
 * 判定：存在阻止出站的规则 → Warn；防火墙配置文件全部开启且无阻止规则 → Pass。
 */
public final class Module18FirewallRules extends AbstractCheckModule {

    public Module18FirewallRules() {
        super("18", "检查本机防火墙规则", FaultLayer.SECURITY_PROXY, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetFirewallProfile 采集防火墙配置文件状态");
        CmdResult profileRaw = context.ps(Probes.KEY_FIREWALL_PROFILES, Probes.FIREWALL_PROFILES);
        List<Map<String, Object>> profiles = context.psJson(Probes.KEY_FIREWALL_PROFILES, Probes.FIREWALL_PROFILES);

        context.step("执行 Get-NetFirewallRule 采集已启用的出站阻止规则");
        CmdResult blockRaw = context.ps(Probes.KEY_FIREWALL_BLOCK, Probes.FIREWALL_BLOCK);
        Map<String, Object> blockSummary = context.psJsonFirst(Probes.KEY_FIREWALL_BLOCK, Probes.FIREWALL_BLOCK);
        long blockCount = MapAccess.number(blockSummary, "Count", 0);
        List<Map<String, Object>> blockRules = MapAccess.objectList(blockSummary, "Rules");

        context.step("汇总防火墙配置与阻止规则明细");
        List<String> profileDetails = new ArrayList<>();
        boolean anyProfileDisabled = false;
        for (Map<String, Object> profile : profiles) {
            boolean enabled = MapAccess.bool(profile, "Enabled");
            profileDetails.add(MapAccess.text(profile, "Name") + "：启用=" + enabled
                    + "，入站默认=" + MapAccess.text(profile, "DefaultInboundAction")
                    + "，出站默认=" + MapAccess.text(profile, "DefaultOutboundAction"));
            if (!enabled) {
                anyProfileDisabled = true;
            }
        }
        List<String> ruleNames = new ArrayList<>();
        for (Map<String, Object> rule : blockRules) {
            ruleNames.add(MapAccess.text(rule, "DisplayName") + "（配置文件 " + MapAccess.text(rule, "Profile") + "）");
        }

        context.step("判定出站规则是否存在异常阻断");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (profiles.isEmpty()) {
            status = Status.INFO;
            summary = "未能读取防火墙配置文件状态";
            diagnosis = "Get-NetFirewallProfile 无返回，可能 Windows Defender 防火墙服务（mpssvc）未运行或被策略托管";
            suggestion = "检查“Windows Defender Firewall”服务状态，或在组策略中确认防火墙配置来源";
        } else if (blockCount > 0) {
            status = Status.WARN;
            summary = "存在 " + blockCount + " 条已启用的出站阻止规则"
                    + (ruleNames.isEmpty() ? "" : "，示例：" + String.join("；", first(ruleNames, 5)));
            diagnosis = "出站阻止规则会直接切断某个程序的对外连接，是“其它机器能上网、只有本机某个程序不能联网”的常见原因";
            suggestion = "1.在“高级安全 Windows Defender 防火墙 → 出站规则”中核对上述规则；"
                    + "2.确认这些规则是否为你或安全软件有意添加；3.如不需要，可在界面中禁用对应规则"
                    + "（本工具只读，不会修改任何防火墙规则）";
        } else if (anyProfileDisabled) {
            status = Status.WARN;
            summary = "未发现出站阻止规则，但存在已关闭的防火墙配置文件：" + String.join("；", profileDetails);
            diagnosis = "防火墙配置文件被关闭会降低本机安全性，虽然不会直接导致网络故障，但属于风险配置";
            suggestion = "建议在“Windows 安全中心 → 防火墙和网络保护”中重新开启对应配置文件的防火墙";
        } else {
            status = Status.PASS;
            summary = "防火墙配置文件全部启用，且不存在已启用的出站阻止规则（" + String.join("；", profileDetails) + "）";
            diagnosis = "防火墙当前不会阻断本机的对外连接，安全策略处于常规状态";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("防火墙配置与出站阻止规则盘点", "BlameNet 汇总：Get-NetFirewallProfile + Get-NetFirewallRule",
                        "配置文件：\n" + (profileDetails.isEmpty() ? "无" : String.join("\n", profileDetails))
                                + "\n\n出站阻止规则总数：" + blockCount
                                + "\n规则示例：\n" + (ruleNames.isEmpty() ? "无" : String.join("\n", ruleNames)))
                .evidence("Get-NetFirewallProfile 原始输出", profileRaw)
                .evidence("Get-NetFirewallRule（出站阻止）原始输出", blockRaw)
                .build();
    }

    private static List<String> first(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }
}
