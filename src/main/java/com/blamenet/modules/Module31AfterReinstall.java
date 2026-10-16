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
import java.util.Locale;
import java.util.Map;

/**
 * 模块 31：重装系统后测试（仅建议，不执行）。
 * <p>
 * 采集：无法自动执行，且用户明确要求不涉及重装；补充采集当前系统版本与磁盘空间，为重装决策提供事实依据。
 * 判定：固定标记为 Manual（不执行，仅建议）；
 * 仅当模块 30（PE/Live 对比）显示 PE 下正常而 Windows 下异常时，才建议考虑系统层修复或重装。
 */
public final class Module31AfterReinstall extends AbstractCheckModule {

    public Module31AfterReinstall() {
        super("31", "重装系统后测试", FaultLayer.EXTERNAL, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("说明本模块明确不执行重装，仅提供建议");
        context.step("采集当前系统信息与磁盘可用空间，为重装决策提供依据");
        List<Map<String, Object>> os = context.psJson("win32OperatingSystem",
                "@(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,OSArchitecture,"
                        + "SystemDrive,@{n='InstallDate';e={if($_.InstallDate){$_.InstallDate.ToString('yyyy-MM-dd HH:mm:ss')}else{''}}}) "
                        + "| ConvertTo-Json -Depth 4");
        List<Map<String, Object>> disks = context.psJson(Probes.KEY_LOGICAL_DISKS, Probes.LOGICAL_DISKS);
        CmdResult diskRaw = context.ps(Probes.KEY_LOGICAL_DISKS, Probes.LOGICAL_DISKS);

        String osText = os.isEmpty() ? "未能采集到操作系统信息"
                : MapAccess.text(os.get(0), "Caption") + "（版本 " + MapAccess.text(os.get(0), "Version")
                + "，Build " + MapAccess.text(os.get(0), "BuildNumber")
                + "，架构 " + MapAccess.text(os.get(0), "OSArchitecture")
                + "，安装时间 " + MapAccess.text(os.get(0), "InstallDate") + "）";

        List<String> diskLines = new ArrayList<>();
        for (Map<String, Object> disk : disks) {
            long size = MapAccess.number(disk, "Size", 0);
            long free = MapAccess.number(disk, "FreeSpace", 0);
            diskLines.add(MapAccess.text(disk, "DeviceID") + " " + MapAccess.text(disk, "VolumeName")
                    + "（文件系统 " + MapAccess.text(disk, "FileSystem")
                    + "，总容量 " + formatGb(size) + "，可用 " + formatGb(free) + "）");
        }

        context.step("判断是否需要给出系统层修复或重装建议");
        Status peStatus = context.statusOf("30");
        boolean peStepDone = false;
        String peConclusion = context.evidenceOutputOf("30", 0);

        String instructions = "仅当“模块 30 的 PE/Linux Live 对比测试显示：PE 下网络正常、而当前 Windows 下异常”时，"
                + "才有必要考虑系统层修复或重装。执行顺序建议：\n"
                + "1.先备份重要数据与驱动信息（建议导出网卡驱动）；\n"
                + "2.尝试“设置 → 系统 → 恢复 → 重置此电脑 → 保留我的文件”，优先保留数据；\n"
                + "3.若重置无效，再考虑全新安装系统，并安装厂商官方驱动；\n"
                + "4.重装完成后重新运行本工具，与本次报告逐项对比。";

        String diagnosis = "本模块不会自动执行重装，也不会修改引导、分区或系统文件。"
                + "重装是成本最高、破坏性最大的手段，因此本工具把它放在最后一步，且必须先有 PE/Live 对比证据支持结论。";

        Status status = Status.MANUAL;
        String summary = "无法在当前系统内自动执行（按设计约定不涉及重装），仅提供建议；"
                + "当前系统：" + osText;

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(instructions)
                .evidenceRaw("外部验证任务", "ManualOperation",
                        "任务类型：系统层修复/重装评估，本工具明确不执行\n"
                                + "当前操作系统：" + osText + "\n"
                                + "磁盘可用空间：\n" + (diskLines.isEmpty() ? "未采集到磁盘信息" : String.join("\n", diskLines))
                                + "\n\n模块 30 状态：" + peStatus.sourceValue()
                                + (peConclusion.isEmpty() ? "" : "；模块 30 证据摘要：" + firstLine(peConclusion)))
                .evidence("磁盘空间采集原始输出", diskRaw)
                .manualTask("重装系统后测试（仅建议，不执行）", instructions,
                        "条件触发（需先完成模块 30 的 PE/Live 对比测试）")
                .build();
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.trim().length() > 160 ? line.trim().substring(0, 160) : line.trim();
            }
        }
        return "";
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
