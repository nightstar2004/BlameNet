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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块 04：检查网卡硬件丢弃计数器。
 * <p>
 * 采集：Get-NetAdapterStatistics，间隔 3 秒连续采样两次对比增量。
 * 判定：ReceivedDiscardedPackets &gt; 0 或 OutboundDiscardedPackets &gt; 0 → Warn（可能驱动/资源问题）；
 * 采样间隔内计数持续增长 → Fail。
 */
public final class Module04AdapterDiscardCounters extends AbstractCheckModule {

    /** 两次采样之间的真实等待时间（秒）。 */
    private static final int SAMPLE_INTERVAL_SECONDS = 3;

    public Module04AdapterDiscardCounters() {
        super("04", "检查网卡硬件丢弃计数器", FaultLayer.HARDWARE_DRIVER, 5, false, 12);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("第一次采样：执行 Get-NetAdapterStatistics");
        CmdResult firstRaw = context.ps(Probes.KEY_ADAPTER_STATS_1, Probes.ADAPTER_STATISTICS);
        List<Map<String, Object>> first = context.psJson(Probes.KEY_ADAPTER_STATS_1, Probes.ADAPTER_STATISTICS);

        context.step("等待 " + SAMPLE_INTERVAL_SECONDS + " 秒后进行第二次采样");
        sleep(SAMPLE_INTERVAL_SECONDS);
        CmdResult secondRaw = context.ps(Probes.KEY_ADAPTER_STATS_2, Probes.ADAPTER_STATISTICS);
        List<Map<String, Object>> second = context.psJson(Probes.KEY_ADAPTER_STATS_2, Probes.ADAPTER_STATISTICS);

        context.step("对比两次采样的丢弃与错误计数增量");
        Map<String, Map<String, Object>> secondByName = new LinkedHashMap<>();
        for (Map<String, Object> row : second) {
            secondByName.put(MapAccess.text(row, "Name"), row);
        }

        List<String> growingAdapters = new ArrayList<>();
        List<String> nonzeroAdapters = new ArrayList<>();
        List<String> errorAdapters = new ArrayList<>();
        List<String> details = new ArrayList<>();

        for (Map<String, Object> row : first) {
            String name = MapAccess.text(row, "Name");
            long received = MapAccess.number(row, "ReceivedDiscardedPackets", 0);
            long outbound = MapAccess.number(row, "OutboundDiscardedPackets", 0);
            long inErrors = MapAccess.number(row, "ReceivedPacketErrors", 0);
            long outErrors = MapAccess.number(row, "OutboundPacketErrors", 0);

            Map<String, Object> next = secondByName.get(name);
            long receivedDelta = next == null ? 0 : MapAccess.number(next, "ReceivedDiscardedPackets", 0) - received;
            long outboundDelta = next == null ? 0 : MapAccess.number(next, "OutboundDiscardedPackets", 0) - outbound;

            details.add(name + "：接收丢弃=" + received + "，发送丢弃=" + outbound
                    + "，接收错误=" + inErrors + "，发送错误=" + outErrors
                    + "，" + SAMPLE_INTERVAL_SECONDS + " 秒内增量 接收丢弃=" + receivedDelta + "、发送丢弃=" + outboundDelta);

            if (receivedDelta > 0 || outboundDelta > 0) {
                growingAdapters.add(name);
            }
            if (received > 0 || outbound > 0) {
                nonzeroAdapters.add(name);
            }
            if (inErrors > 0 || outErrors > 0) {
                errorAdapters.add(name);
            }
        }

        context.step("按丢弃计数判定驱动或硬件异常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;

        if (first.isEmpty()) {
            status = Status.FAIL;
            summary = "未能采集到任何网卡的统计数据";
            diagnosis = "Get-NetAdapterStatistics 无返回，可能所有网卡均未启用或采集失败";
            suggestion = "确认网卡已启用后重新运行本工具；若问题持续，请在“设备管理器”中检查网卡驱动状态";
        } else if (!growingAdapters.isEmpty()) {
            status = Status.FAIL;
            summary = "网卡在 " + SAMPLE_INTERVAL_SECONDS + " 秒采样窗口内丢弃计数持续增长：" + String.join("、", growingAdapters);
            diagnosis = "网卡正在持续丢弃数据包，常见原因为驱动缺陷、缓冲区不足、网线质量差、网卡节能丢包或硬件老化";
            suggestion = "1.更新网卡驱动到厂商官方最新版本；2.更换一根高质量网线并更换路由器端口；"
                    + "3.关闭网卡节能选项（见模块 22、23）；4.关闭“大量发送卸载/Large Send Offload”后对比测试"
                    + "（本工具只读，不会修改任何网卡属性）";
        } else if (!errorAdapters.isEmpty()) {
            status = Status.WARN;
            summary = "网卡存在错误计数（非零）：" + String.join("、", errorAdapters);
            diagnosis = "历史累计错误包说明链路层曾出现质量问题，可能是网线老化、水晶头接触不良或端口协商异常";
            suggestion = "更换网线并重新插拔两端水晶头；如为无线，请检查信号强度与信道干扰";
        } else if (!nonzeroAdapters.isEmpty()) {
            status = Status.WARN;
            summary = "网卡历史累计丢弃计数非零（当前未继续增长）：" + String.join("、", nonzeroAdapters);
            diagnosis = "历史上出现过丢弃，但当前采样窗口内不再增长，可能是偶发链路抖动或早期驱动问题";
            suggestion = "建议持续观察；若网络仍不稳定，请更新驱动并检查网线质量";
        } else {
            status = Status.PASS;
            summary = "所有网卡的接收/发送丢弃计数与错误计数均为 0，采样窗口内无增长";
            diagnosis = "网卡驱动与硬件链路工作正常，未发现丢包迹象";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("丢弃计数器盘点（第一次采样）", "BlameNet 汇总：基于 Get-NetAdapterStatistics 第一次采样结果",
                        String.join("\n", details))
                .evidence("Get-NetAdapterStatistics 第一次采样原始输出", firstRaw)
                .evidence("Get-NetAdapterStatistics 第二次采样原始输出（间隔 " + SAMPLE_INTERVAL_SECONDS + " 秒）", secondRaw)
                .build();
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
