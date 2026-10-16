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
 * 模块 30：PE 或 Linux Live USB 测试（外部验证项）。
 * <p>
 * 采集：无法在当前 Windows 系统内自动执行；补充采集可移动磁盘信息，帮助用户判断是否已准备好启动盘。
 * 判定：固定标记为 Manual（待外部验证），并生成详细操作指南。
 */
public final class Module30PeLiveUsb extends AbstractCheckModule {

    public Module30PeLiveUsb() {
        super("30", "PE或Linux Live USB测试", FaultLayer.EXTERNAL, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("说明本模块无法在当前系统内自动执行");
        context.step("采集可移动磁盘信息，判断是否已具备制作启动盘的条件");
        CmdResult removableRaw = context.ps(Probes.KEY_REMOVABLE_DRIVES, Probes.REMOVABLE_DRIVES);
        List<Map<String, Object>> removable = context.psJson(Probes.KEY_REMOVABLE_DRIVES, Probes.REMOVABLE_DRIVES);

        List<String> drives = new ArrayList<>();
        for (Map<String, Object> drive : removable) {
            long size = MapAccess.number(drive, "Size", 0);
            drives.add(MapAccess.text(drive, "Model") + "（接口 " + MapAccess.text(drive, "InterfaceType")
                    + "，容量 " + formatSize(size) + "，状态 " + MapAccess.text(drive, "Status") + "）");
        }

        context.step("生成 PE/Linux Live 对比测试任务");
        String instructions = "1.准备一个 8GB 以上的 U 盘，使用官方工具制作 Linux Live（如 Ubuntu）或 Windows PE 启动盘；"
                + "2.在目标电脑上从 U 盘启动（不安装、不写盘，仅运行于内存，不影响本机 Windows 系统）；"
                + "3.在 PE/Live 环境中检查网卡是否被识别、是否能获取 IP、是否能打开网页；"
                + "4.将结果回填到本工具进行二次分析：PE/Live 下正常 → 故障在 Windows 系统层；PE/Live 下同样异常 → 偏硬件/链路/路由器。";

        String diagnosis = "若 PE/Live 系统下网络正常，则故障属于 Windows 系统层面（驱动、服务、策略或软件冲突）；"
                + "若 PE/Live 系统下同样异常，则偏向网卡硬件、网线、路由器或运营商链路故障。"
                + "这是区分“系统问题”与“硬件问题”最有效的一步，因此本工具不会跳过该步骤。";

        return result()
                .status(Status.MANUAL)
                .summary("无法在当前 Windows 系统内自动执行，需要用户使用外部启动环境验证"
                        + (drives.isEmpty() ? "（当前未检测到可移动磁盘）" : "（当前检测到 " + drives.size() + " 个可移动磁盘）"))
                .diagnosis(diagnosis)
                .suggestion(instructions + "本工具只读，不会制作启动盘、不会修改引导、不会写入任何磁盘")
                .evidenceRaw("外部验证任务", "ManualOperation",
                        "任务类型：外部硬件环境对比测试，本工具无法在当前系统内自动执行\n"
                                + "可移动磁盘检测：\n" + (drives.isEmpty() ? "未检测到可移动磁盘" : String.join("\n", drives)))
                .evidence("可移动磁盘采集原始输出", removableRaw)
                .manualTask("PE 或 Linux Live USB 网络对比测试", instructions,
                        "建议优先完成（用于区分硬件故障与系统故障）")
                .build();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.1f GB", gb);
    }
}
