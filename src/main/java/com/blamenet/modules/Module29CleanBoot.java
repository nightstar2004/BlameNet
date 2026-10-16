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
 * 模块 29：安全模式 / 干净启动测试（外部验证项）。
 * <p>
 * 采集：读取启动项、非 Microsoft 服务与第三方正在运行的系统驱动；不自动重启。
 * 判定：存在大量第三方网络过滤驱动/服务/启动项 → Warn；否则输出 Manual 并给出干净启动操作指引。
 */
public final class Module29CleanBoot extends AbstractCheckModule {

    /** 启动项数量阈值。 */
    private static final int STARTUP_THRESHOLD = 10;

    /** 第三方运行中服务数量阈值。 */
    private static final int SERVICE_THRESHOLD = 10;

    /** 第三方运行中驱动数量阈值（网络过滤驱动通常在此列表中出现）。 */
    private static final int DRIVER_THRESHOLD = 3;

    public Module29CleanBoot() {
        super("29", "安全模式/干净启动测试", FaultLayer.EXTERNAL, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("读取开机启动项（Win32_StartupCommand）");
        CmdResult startupRaw = context.ps(Probes.KEY_STARTUP_COMMANDS, Probes.STARTUP_COMMANDS);
        List<Map<String, Object>> startups = context.psJson(Probes.KEY_STARTUP_COMMANDS, Probes.STARTUP_COMMANDS);

        context.step("读取非系统目录的第三方运行中服务");
        CmdResult serviceRaw = context.ps(Probes.KEY_THIRD_PARTY_SERVICES, Probes.THIRD_PARTY_SERVICES);
        List<Map<String, Object>> services = context.psJson(Probes.KEY_THIRD_PARTY_SERVICES, Probes.THIRD_PARTY_SERVICES);

        context.step("读取第三方运行中的系统驱动（含网络过滤驱动）");
        CmdResult driverRaw = context.ps(Probes.KEY_THIRD_PARTY_DRIVERS, Probes.THIRD_PARTY_DRIVERS);
        List<Map<String, Object>> drivers = context.psJson(Probes.KEY_THIRD_PARTY_DRIVERS, Probes.THIRD_PARTY_DRIVERS);

        context.step("汇总第三方组件数量并判定是否需要干净启动对比");
        boolean anomaly = startups.size() >= STARTUP_THRESHOLD
                || services.size() >= SERVICE_THRESHOLD
                || drivers.size() >= DRIVER_THRESHOLD;

        Status status = anomaly ? Status.WARN : Status.MANUAL;
        String summary;
        String diagnosis;
        if (anomaly) {
            summary = "检测到较多第三方组件——启动项 " + startups.size() + " 项、第三方运行中服务 " + services.size()
                    + " 个、第三方运行中驱动 " + drivers.size() + " 个；建议执行干净启动对比测试";
            diagnosis = "第三方驱动（尤其是网络过滤驱动，如 VPN、抓包、安全软件）会挂接到网络协议栈上，"
                    + "是“重装驱动无效、卸载软件后恢复正常”类疑难网络故障的常见原因";
        } else {
            summary = "本模块无法在当前系统中自动完成；已采集启动项 " + startups.size() + " 项、第三方服务 "
                    + services.size() + " 个、第三方驱动 " + drivers.size() + " 个，数量未达告警阈值，"
                    + "干净启动测试作为可选验证步骤";
            diagnosis = "当前第三方组件数量不多，干净启动主要用于排除“某个软件与网络栈冲突”的可能性";
        }

        String instructions = "1.按 Win+R 输入 msconfig，切换到“服务”页，勾选“隐藏所有 Microsoft 服务”后点击“全部禁用”；"
                + "2.切换到“启动”页，点击“打开任务管理器”，把不需要自启的启动项全部禁用；"
                + "3.重启电脑（重启操作需你自行确认，本工具不会自动重启）；"
                + "4.重启后重新运行本工具对比模块 12、14、17 的结果；"
                + "5.若网络恢复正常，则逐个启用服务/启动项以定位冲突来源。";

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(instructions + "本工具只读，不会修改任何启动项与服务状态，也不会自动重启系统")
                .evidenceRaw("第三方组件盘点", "BlameNet 汇总：Win32_StartupCommand + Win32_Service + Win32_SystemDriver",
                        "启动项（" + startups.size() + "）：\n" + summarize(startups, "Name", "Location")
                                + "\n\n第三方运行中服务（" + services.size() + "）：\n" + summarize(services, "Name", "DisplayName")
                                + "\n\n第三方运行中驱动（" + drivers.size() + "）：\n" + summarize(drivers, "Name", "DisplayName"))
                .evidence("启动项原始输出", startupRaw)
                .evidence("第三方服务原始输出", serviceRaw)
                .evidence("第三方驱动原始输出", driverRaw)
                .manualTask("安全模式 / 干净启动对比测试", instructions,
                        anomaly ? "建议优先完成（已检出较多第三方组件）" : "可选")
                .build();
    }

    private static String summarize(List<Map<String, Object>> rows, String... keys) {
        if (rows.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(MapAccess.text(row, key));
            }
            lines.add(sb.toString());
        }
        return String.join("\n", lines);
    }
}
