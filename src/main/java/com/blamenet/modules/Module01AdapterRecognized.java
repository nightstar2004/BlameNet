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
 * 模块 01：检查网络适配器是否被系统识别。
 * <p>
 * 采集：Get-NetAdapter | Select Name, InterfaceDescription, Status, LinkSpeed, MacAddress
 * 判定：无任何适配器 → Fail（硬件未识别或驱动未加载）；Disabled → Warn（适配器被禁用）；
 * Disconnected → Warn（链路未连接）；存在 Up → Pass。
 */
public final class Module01AdapterRecognized extends AbstractCheckModule {

    public Module01AdapterRecognized() {
        super("01", "检查网络适配器是否被系统识别", FaultLayer.HARDWARE_DRIVER, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetAdapter 采集本机网络适配器列表");
        List<Map<String, Object>> adapters = context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER);
        CmdResult raw = context.ps(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER);

        context.step("统计有线网卡、无线网卡与虚拟网卡数量");
        int wired = 0;
        int wireless = 0;
        int virtualAdapters = 0;
        int up = 0;
        int disabled = 0;
        int disconnected = 0;
        int notPresent = 0;
        List<String> upNames = new ArrayList<>();
        List<String> problemNames = new ArrayList<>();

        for (Map<String, Object> adapter : adapters) {
            String name = MapAccess.text(adapter, "Name");
            String mediaType = MapAccess.text(adapter, "MediaType").toLowerCase(Locale.ROOT);
            String status = MapAccess.text(adapter, "Status");
            boolean virtual = MapAccess.bool(adapter, "Virtual");
            if (virtual) {
                virtualAdapters++;
            } else if (NetFacts.isWireless(adapter)) {
                wireless++;
            } else if (mediaType.contains("802.3")) {
                wired++;
            }
            switch (status.toLowerCase(Locale.ROOT)) {
                case "up" -> {
                    up++;
                    upNames.add(name);
                }
                case "disabled" -> {
                    disabled++;
                    problemNames.add(name + "（已禁用）");
                }
                case "disconnected" -> {
                    disconnected++;
                    problemNames.add(name + "（链路未连接）");
                }
                case "not present" -> notPresent++;
                default -> {
                    // 其他状态不单独计数，明细仍保留在证据中
                }
            }
        }

        context.step("判定网卡识别结果与链路状态");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;

        if (adapters.isEmpty()) {
            status = Status.FAIL;
            summary = "系统未识别到任何网络适配器";
            diagnosis = "操作系统枚举不到网络适配器，通常是网卡硬件未被识别、驱动未加载，或设备被禁用后从枚举结果中移除";
            suggestion = "1.打开“设备管理器”查看“网络适配器”是否存在黄色感叹号设备；"
                    + "2.检查 BIOS/UEFI 中是否禁用了板载网卡；3.若为 USB/PCIe 网卡，尝试更换插槽后重新运行本工具"
                    + "（本工具只读，不会启用或安装任何驱动）";
        } else if (up > 0) {
            status = Status.PASS;
            summary = String.format("系统成功识别 %d 块适配器（有线 %d、无线 %d、虚拟 %d），其中 %d 块处于 Up 状态：%s",
                    adapters.size(), wired, wireless, virtualAdapters, up, String.join("、", upNames));
            diagnosis = "网卡硬件已被操作系统识别，且至少有一块适配器处于工作状态";
            suggestion = disconnected > 0 || disabled > 0
                    ? "存在未连接或被禁用的适配器：" + String.join("、", problemNames) + "，若你不使用它们可忽略"
                    : "无";
        } else if (disabled > 0) {
            status = Status.WARN;
            summary = "识别到 " + adapters.size() + " 块适配器，但全部处于禁用状态：" + String.join("、", problemNames);
            diagnosis = "网卡硬件已被识别，但适配器被禁用，网络功能不可用";
            suggestion = "在“网络连接”或“设备管理器”中启用对应适配器后重新运行本工具（本工具不会自动启用网卡）";
        } else if (disconnected > 0) {
            status = Status.WARN;
            summary = "识别到 " + adapters.size() + " 块适配器，但链路均未连接：" + String.join("、", problemNames);
            diagnosis = "网卡驱动已加载，但网线未插好、对端设备未通电或无线未连接";
            suggestion = "检查网线两端是否插紧、路由器/交换机端口指示灯是否点亮；无线场景请确认已连接到目标 SSID";
        } else {
            status = Status.WARN;
            summary = "识别到 " + adapters.size() + " 块适配器，但没有处于 Up 状态的适配器"
                    + (notPresent > 0 ? "（其中 " + notPresent + " 块处于 Not Present 状态）" : "");
            diagnosis = "适配器状态异常，未建立可用链路";
            suggestion = "请在“设备管理器”中查看网卡状态，并在“网络连接”中确认适配器已启用、网线已连接";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidence("Get-NetAdapter 适配器列表原始输出", raw)
                .build();
    }
}
