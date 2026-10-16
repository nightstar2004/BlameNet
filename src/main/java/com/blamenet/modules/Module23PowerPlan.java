package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.Parsers;
import com.blamenet.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模块 23：检查电源计划中的网络节能设置。
 * <p>
 * 采集：powercfg /getactivescheme + powercfg /query（PCI Express 子组与无线适配器子组）。
 * 判定：PCIe 链接状态电源管理非“关闭” → Warn；无线节能模式非“最高性能” → Warn。
 */
public final class Module23PowerPlan extends AbstractCheckModule {

    /** PCI Express 子组 GUID。 */
    private static final String SUB_PCIEXPRESS = "501a4d13-42af-4429-9fd1-a8218c268e20";

    /** 无线适配器设置子组 GUID。 */
    private static final String SUB_WIRELESS = "19cbb8fa-5279-450e-9fac-8a3d5fedd0c1";

    /** 链接状态电源管理设置的 GUID。 */
    private static final String SETTING_ASPM = "ee12f906-d277-404b-b6da-e5fa1a576df5";

    public Module23PowerPlan() {
        super("23", "检查电源计划中的网络节能设置", FaultLayer.SERVICE_SYSTEM, 5);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 powercfg /getactivescheme 采集当前电源计划");
        CmdResult schemeRaw = context.cmd(Probes.KEY_POWERCFG_SCHEME, Probes.CMDP_POWERCFG_SCHEME);
        String schemeText = firstNonBlankLine(schemeRaw.output());

        context.step("执行 powercfg /query 采集 PCI Express 节能设置");
        CmdResult pcieRaw = context.cmd(Probes.KEY_POWERCFG_PCIE, Probes.CMDP_POWERCFG_PCIE);
        List<Parsers.PowerSetting> pcieSettings = Parsers.parsePowercfgQuery(pcieRaw.output());
        boolean pcieQueryFailed = pcieSettings.isEmpty() || looksLikeError(pcieRaw.output());

        context.step("执行 powercfg /query 采集无线适配器节能设置");
        CmdResult wirelessRaw = context.cmd(Probes.KEY_POWERCFG_WIRELESS, Probes.CMDP_POWERCFG_WIRELESS);
        List<Parsers.PowerSetting> wirelessSettings = Parsers.parsePowercfgQuery(wirelessRaw.output());
        boolean wirelessQueryFailed = wirelessSettings.isEmpty() || looksLikeError(wirelessRaw.output());

        context.step("解析设置索引并判定节能策略");
        List<String> details = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        Parsers.PowerSetting aspm = find(pcieSettings, SETTING_ASPM);
        if (aspm != null) {
            details.add("PCIe 链接状态电源管理（AC）：" + aspm.acValueName() + "，索引=" + aspm.acIndex()
                    + "，可选值=" + aspm.indexNames());
            if (aspm.acIndex() != 0) {
                problems.add("PCI Express 链接状态电源管理当前为“" + aspm.acValueName() + "”，而非“关闭”");
            }
        }
        for (Parsers.PowerSetting setting : wirelessSettings) {
            String name = TextUtil.isBlank(setting.settingName()) ? setting.settingGuid() : setting.settingName();
            details.add("无线适配器设置 " + name + "（AC）：" + setting.acValueName() + "，索引=" + setting.acIndex()
                    + "，可选值=" + setting.indexNames());
            String value = setting.acValueName();
            boolean isMaxPerformance = value.contains("最高性能");
            if (!isMaxPerformance && (name.contains("节能") || name.contains("power saving"))) {
                problems.add("无线适配器节能模式当前为“" + value + "”，而非“最高性能”");
            }
        }

        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (pcieQueryFailed && wirelessQueryFailed) {
            status = Status.INFO;
            summary = "未能解析电源计划的子组设置，无法判定网络节能策略（当前电源计划：" + schemeText + "）";
            diagnosis = "powercfg 子组查询无有效输出，可能该系统版本不支持对应子组别名/GUID，或电源计划被厂商工具托管";
            suggestion = "可在管理员命令行中手动执行 powercfg /query 查看完整电源设置"
                    + "（本工具只读，不会修改任何电源设置）";
        } else if (!problems.isEmpty()) {
            status = Status.WARN;
            summary = "电源计划中存在网络节能设置：" + String.join("；", problems);
            diagnosis = "电源计划中的节能策略会在空闲时降低 PCIe 链路或无线适配器的供电与性能，"
                    + "常见表现为“闲置一会儿后网络变慢或掉线，动一下鼠标就恢复”";
            suggestion = "在“控制面板 → 电源选项 → 更改计划设置 → 更改高级电源设置”中："
                    + "1.将“PCI Express → 链接状态电源管理”设为“关闭”；"
                    + "2.将“无线适配器设置 → 节能模式”设为“最高性能”；"
                    + "3.如需彻底排除，可临时切换到“高性能”电源计划对比测试"
                    + "（本工具只读，不会修改任何电源设置）";
        } else {
            status = Status.PASS;
            summary = "电源计划中的网络节能设置已关闭（当前计划：" + schemeText + "）";
            diagnosis = "PCIe 链接状态电源管理为关闭、无线节能模式为最高性能，不会因此造成断流";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("电源计划与网络节能设置盘点", "BlameNet 汇总：powercfg /getactivescheme + powercfg /query",
                        "当前电源计划：" + schemeText
                                + "\nPCIe 子组查询：" + (pcieQueryFailed ? "无有效输出" : "解析到 " + pcieSettings.size() + " 个设置项")
                                + "\n无线子组查询：" + (wirelessQueryFailed ? "无有效输出" : "解析到 " + wirelessSettings.size() + " 个设置项")
                                + "\n\n" + (details.isEmpty() ? "无明细" : String.join("\n", details)))
                .evidence("powercfg /getactivescheme 原始输出", schemeRaw)
                .evidence("powercfg /query PCIe 子组原始输出", pcieRaw)
                .evidence("powercfg /query 无线适配器子组原始输出", wirelessRaw)
                .build();
    }

    private static Parsers.PowerSetting find(List<Parsers.PowerSetting> settings, String guid) {
        for (Parsers.PowerSetting setting : settings) {
            if (guid.equalsIgnoreCase(TextUtil.nvl(setting.settingGuid()))) {
                return setting;
            }
        }
        return settings.isEmpty() ? null : settings.get(0);
    }

    private static boolean looksLikeError(String output) {
        String text = TextUtil.nvl(output).toLowerCase(Locale.ROOT);
        return text.contains("无法") || text.contains("不可用") || text.contains("error")
                || text.contains("invalid") || text.contains("参数");
    }

    private static String firstNonBlankLine(String output) {
        for (String line : TextUtil.normalize(output).split("\n")) {
            if (TextUtil.isNotBlank(line)) {
                return line.trim();
            }
        }
        return "未知";
    }
}
