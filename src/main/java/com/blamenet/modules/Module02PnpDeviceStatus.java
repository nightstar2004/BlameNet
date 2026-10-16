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
 * 模块 02：检查设备管理器中的网卡状态。
 * <p>
 * 采集：Get-PnpDevice -Class Net | Select FriendlyName, Status, Problem, ProblemDescription
 * 判定：Status = OK 且 Problem = 0 → Pass；Problem 非 0 → Fail，并记录错误码（10/28/43/56）。
 */
public final class Module02PnpDeviceStatus extends AbstractCheckModule {

    public Module02PnpDeviceStatus() {
        super("02", "检查设备管理器中的网卡状态", FaultLayer.HARDWARE_DRIVER, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-PnpDevice 采集网卡设备状态");
        List<Map<String, Object>> devices = context.psJson(Probes.KEY_PNP_NET, Probes.PNP_NET);
        CmdResult raw = context.ps(Probes.KEY_PNP_NET, Probes.PNP_NET);

        context.step("统计正常设备与问题设备");
        int okCount = 0;
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> device : devices) {
            String status = MapAccess.text(device, "Status");
            long problem = MapAccess.number(device, "Problem", 0);
            String friendlyName = MapAccess.text(device, "FriendlyName");
            if ("OK".equalsIgnoreCase(status) && problem == 0) {
                okCount++;
            } else {
                String description = MapAccess.text(device, "ProblemDescription");
                problems.add(friendlyName + "（状态=" + status + "，问题代码=" + problem
                        + (description.isEmpty() ? "" : "，" + description) + "：" + problemMeaning(problem) + "）");
            }
        }

        context.step("按错误码推断故障原因");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (devices.isEmpty()) {
            status = Status.FAIL;
            summary = "设备管理器中未查询到任何 Net 类设备";
            diagnosis = "系统未枚举到网络类设备，可能网卡硬件缺失、被 BIOS 禁用或系统设备枚举异常";
            suggestion = "在“设备管理器”中显示隐藏设备后检查是否存在网卡；确认 BIOS 中板载网卡未被禁用";
        } else if (problems.isEmpty()) {
            status = Status.PASS;
            summary = "设备管理器中 " + devices.size() + " 个网络类设备全部状态正常（Status = OK，Problem = 0）";
            diagnosis = "网卡驱动已正确加载，设备管理器无异常设备";
            suggestion = "无";
        } else {
            status = Status.FAIL;
            summary = problems.size() + " 个网络类设备存在问题：" + String.join("；", problems);
            diagnosis = "设备管理器中存在带错误代码的网卡设备，驱动层异常会导致该网卡完全不可用";
            suggestion = "1.在“设备管理器”中右键异常设备 → 卸载设备并勾选删除驱动，然后点击“扫描检测硬件改动”让系统重装驱动；"
                    + "2.前往笔记本/主板厂商官网下载对应型号的官方网卡驱动安装；3.若问题代码为 43，请关机断电后重新插拔硬件"
                    + "（本工具只读，不会卸载或安装任何驱动）";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidence("Get-PnpDevice 网卡设备原始输出", raw)
                .build();
    }

    /** 常见设备管理器错误代码释义。 */
    private static String problemMeaning(long problem) {
        return switch ((int) problem) {
            case 0 -> "设备工作正常";
            case 1 -> "设备未正确配置";
            case 3 -> "驱动损坏或系统资源不足";
            case 10 -> "设备无法启动";
            case 12 -> "资源不足，无法分配空闲资源";
            case 18 -> "需要重新安装驱动程序";
            case 19 -> "注册表信息不完整或已损坏";
            case 22 -> "设备已被禁用";
            case 24 -> "设备未安装、工作不正常或未安装全部驱动";
            case 28 -> "未安装设备驱动程序";
            case 31 -> "无法加载该设备所需的驱动程序";
            case 43 -> "设备已被停止，因为该设备报告了问题";
            case 45 -> "设备当前未连接";
            case 52 -> "无法验证该设备驱动程序的数字签名";
            case 56 -> "需要进行注册表更改才能加载该设备";
            default -> "未知错误代码，请参考设备管理器提示";
        };
    }
}
