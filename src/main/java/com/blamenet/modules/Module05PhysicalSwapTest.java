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
 * 模块 05：物理连接替换测试（外部验证项）。
 * <p>
 * 采集：读取链路速度、双工模式、错误计数；无法自动换线。
 * 判定：若模块 01-04 存在异常，自动标记“建议进行物理替换测试”；本模块固定输出 Manual，
 * 并生成结构化的外部验证任务，指导用户完成换线、换端口、换插槽等对比测试。
 */
public final class Module05PhysicalSwapTest extends AbstractCheckModule {

    public Module05PhysicalSwapTest() {
        super("05", "物理连接替换测试", FaultLayer.HARDWARE_DRIVER, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("采集链路速率与协商模式（无法自动执行换线动作）");
        List<Map<String, Object>> adapters = context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER);
        CmdResult advanced = context.ps(Probes.KEY_ADAPTER_ADVANCED, Probes.ADAPTER_ADVANCED);
        List<Map<String, Object>> advancedList = context.psJson(Probes.KEY_ADAPTER_ADVANCED, Probes.ADAPTER_ADVANCED);

        List<String> linkInfo = new ArrayList<>();
        for (Map<String, Object> adapter : adapters) {
            linkInfo.add(MapAccess.text(adapter, "Name") + "：描述=" + MapAccess.text(adapter, "InterfaceDescription")
                    + "，链路速度=" + MapAccess.text(adapter, "LinkSpeed")
                    + "，媒体类型=" + MapAccess.text(adapter, "MediaType")
                    + "，状态=" + MapAccess.text(adapter, "Status")
                    + "，连接状态=" + MapAccess.text(adapter, "MediaConnectionState"));
        }
        for (Map<String, Object> item : advancedList) {
            linkInfo.add(MapAccess.text(item, "Name") + " / " + MapAccess.text(item, "DisplayName")
                    + " = " + MapAccess.text(item, "DisplayValue"));
        }

        context.step("汇总模块 01-04 的异常标记");
        List<String> relatedProblems = new ArrayList<>();
        for (String moduleId : List.of("01", "02", "03", "04")) {
            if (context.isProblem(moduleId)) {
                ModuleResult result = context.resultOf(moduleId);
                relatedProblems.add(moduleId + " " + (result == null ? "" : result.moduleName)
                        + "：" + (result == null ? "状态异常" : result.summary));
            }
        }

        context.step("生成物理替换对比测试任务说明");
        Status status = Status.MANUAL;
        String summary;
        String diagnosis;
        String suggestion;
        if (!relatedProblems.isEmpty()) {
            summary = "本模块无法在当前系统中自动执行；前置模块 01-04 存在异常，建议进行物理替换对比测试";
            diagnosis = "硬件/驱动层已有异常信号：" + String.join("；", relatedProblems)
                    + "。物理替换测试用于区分“网卡本体 / 网线 / 路由器端口 / 插槽”四类原因";
        } else {
            summary = "本模块无法在当前系统中自动执行；前置模块 01-04 未发现异常，物理替换测试为可选步骤";
            diagnosis = "网卡识别、设备状态、无线接口与丢弃计数器均正常，物理链路暂无异常证据，"
                    + "替换测试仅在网络仍然不稳定时作为验证手段";
        }
        suggestion = "1.记录当前链路速度与双工模式（见证据）；2.更换一根已知良好的网线；"
                + "3.更换路由器/交换机上的另一个端口；4.台式机可尝试更换 PCIe 插槽；"
                + "5.无线场景可换用 USB 无线网卡对比；6.每次更换后重新运行本工具，比较模块 01-04 与模块 12 的结果。"
                + "本工具只读，不会自动执行任何替换动作";

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("链路速率与协商模式采集结果", "Get-NetAdapter + Get-NetAdapterAdvancedProperty（只读读取）",
                        String.join("\n", linkInfo))
                .evidence("网卡高级属性原始输出（速率/双工/流控）", advanced)
                .manualTask("物理连接替换测试：更换网线 / 路由器端口 / PCIe 插槽 / 无线网卡",
                        suggestion,
                        relatedProblems.isEmpty() ? "可选" : "建议优先完成（用于区分网卡、网线、端口与插槽故障）")
                .build();
    }
}
