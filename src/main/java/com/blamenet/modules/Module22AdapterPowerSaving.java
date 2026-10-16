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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 22：取消网卡省电设置（只读检查，不修改）。
 * <p>
 * 采集：Get-NetAdapterPowerManagement，并以 WMI MSPower_DeviceEnable 作为交叉验证。
 * 判定：AllowComputerToTurnOffDevice = True → Warn（可能导致间歇断线）。
 */
public final class Module22AdapterPowerSaving extends AbstractCheckModule {

    public Module22AdapterPowerSaving() {
        super("22", "取消网卡省电设置", FaultLayer.SERVICE_SYSTEM, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetAdapterPowerManagement 采集网卡电源管理设置");
        CmdResult raw = context.ps(Probes.KEY_ADAPTER_POWER, Probes.ADAPTER_POWER);
        List<Map<String, Object>> settings = context.psJson(Probes.KEY_ADAPTER_POWER, Probes.ADAPTER_POWER);

        context.step("执行 WMI MSPower_DeviceEnable 交叉验证");
        CmdResult wmiRaw = context.ps(Probes.KEY_DEVICE_POWER_WMI, Probes.DEVICE_POWER_WMI);
        List<Map<String, Object>> wmi = context.psJson(Probes.KEY_DEVICE_POWER_WMI, Probes.DEVICE_POWER_WMI);

        context.step("解析允许关闭设备与唤醒相关设置");
        List<String> allowedOff = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (Map<String, Object> setting : settings) {
            String name = MapAccess.text(setting, "Name");
            boolean allowOff = Enums.truthy(setting.get("AllowComputerToTurnOffDevice"));
            details.add(name + "：允许系统关闭此设备=" + allowOff
                    + "，断开时休眠=" + MapAccess.text(setting, "DeviceSleepOnDisconnect")
                    + "，魔术包唤醒=" + MapAccess.text(setting, "WakeOnMagicPacket")
                    + "，模式唤醒=" + MapAccess.text(setting, "WakeOnPattern"));
            if (allowOff) {
                allowedOff.add(name);
            }
        }
        int wmiEnabled = 0;
        for (Map<String, Object> item : wmi) {
            if (Enums.truthy(item.get("Enable"))) {
                wmiEnabled++;
            }
        }

        context.step("判定节能设置是否可能造成掉线");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (settings.isEmpty() && wmiEnabled > 0) {
            status = Status.WARN;
            summary = "Get-NetAdapterPowerManagement 无返回，但 WMI 显示有 " + wmiEnabled + " 个设备的节能开关处于启用状态";
            diagnosis = "通过 WMI MSPower_DeviceEnable 检测到节能开关启用，网卡可能在空闲时被断电，造成间歇性断连";
            suggestion = "设备管理器 → 网卡属性 → 电源管理，取消勾选【允许计算机关闭此设备以节约电源】"
                    + "（本工具只读，不会修改任何配置）";
        } else if (settings.isEmpty()) {
            status = Status.INFO;
            summary = "未能读取网卡电源管理设置（" + (wmi.isEmpty() ? "WMI 交叉验证同样无数据" : "WMI 交叉验证返回 " + wmi.size() + " 条记录") + "）";
            diagnosis = "网卡驱动可能未提供电源管理接口，或该网卡不支持关机节能特性";
            suggestion = "在“设备管理器 → 网卡 → 属性 → 电源管理”中人工确认是否存在节能选项";
        } else if (!allowedOff.isEmpty()) {
            status = Status.WARN;
            summary = "网卡电源管理允许系统关闭设备，可能引发间歇性断连：" + String.join("、", allowedOff);
            diagnosis = "网卡节能选项开启时，高负载或空闲场景下网卡可能被系统断电休眠，表现为“网络忽然掉线，几秒后自动恢复”；"
                    + "这在笔记本上尤其常见，也是“下载中途断流”的高频原因";
            suggestion = "设备管理器 → 网卡属性 → 电源管理，取消勾选【允许计算机关闭此设备以节约电源】；"
                    + "同时在“电源选项”中把无线适配器节能模式设为最高性能（见模块 23）"
                    + "（本工具只读，不会修改任何配置）";
        } else {
            // 网卡级设置（Get-NetAdapterPowerManagement）是判定依据；
            // WMI MSPower_DeviceEnable 覆盖全机所有设备（含非网卡设备），只作参考信息，
            // 不能据此把“网卡已关闭节能”判定为节能开启。
            status = Status.PASS;
            summary = "全部已检查网卡均未开启“允许计算机关闭此设备”节能选项（共 " + settings.size() + " 块）"
                    + (wmiEnabled > 0
                    ? "；参考信息：WMI 全机共有 " + wmiEnabled + " 个设备（含非网卡设备）允许节能关闭，"
                    + "与网卡级设置无关" : "");
            diagnosis = "网卡电源管理设置符合排查要求，不会因网卡休眠造成间歇性断连";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("网卡电源管理盘点", "BlameNet 汇总：Get-NetAdapterPowerManagement",
                        (details.isEmpty() ? "无数据" : String.join("\n", details))
                                + "\n\nWMI MSPower_DeviceEnable 中 Enable=true 的数量：" + wmiEnabled)
                .evidence("Get-NetAdapterPowerManagement 原始输出", raw)
                .evidence("WMI MSPower_DeviceEnable 原始输出", wmiRaw)
                .build();
    }
}
