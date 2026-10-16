package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.MapAccess;
import com.blamenet.parse.Parsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 03：检查 Wi-Fi 接口详情。
 * <p>
 * 采集：netsh wlan show interfaces
 * 判定：无 Wi-Fi 接口 → Info（不适用）；信号强度 &lt; 50% → Warn；
 * 连接状态非 connected → 若本机还有其他在线网卡则 Warn（无线未连接但可用有线），
 * 否则 Fail（无任何可用链路）。
 */
public final class Module03WlanInterface extends AbstractCheckModule {

    public Module03WlanInterface() {
        super("03", "检查 Wi-Fi 接口详情", FaultLayer.HARDWARE_DRIVER, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 netsh wlan show interfaces 采集无线接口信息");
        CmdResult wlanRaw = context.cmd(Probes.KEY_WLAN_INTERFACES, Probes.CMDP_WLAN_INTERFACES);
        List<Parsers.WlanInterface> interfaces = Parsers.parseNetshWlan(wlanRaw.output());

        context.step("从网卡列表统计无线适配器数量");
        List<Map<String, Object>> adapters = context.psJson(Probes.KEY_NET_ADAPTER, Probes.NET_ADAPTER);
        int wirelessAdapters = 0;
        boolean anyOtherAdapterUp = false;
        for (Map<String, Object> adapter : adapters) {
            if (NetFacts.isWireless(adapter)) {
                wirelessAdapters++;
            } else if ("Up".equalsIgnoreCase(MapAccess.text(adapter, "Status"))) {
                anyOtherAdapterUp = true;
            }
        }

        context.step("解析无线接口状态、SSID 与信号强度");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;

        if (interfaces.isEmpty() && wirelessAdapters == 0) {
            status = Status.INFO;
            summary = "本机没有无线网卡，模块不适用";
            diagnosis = "系统未检测到无线网络接口，该检查项跳过判定";
            suggestion = "无";
        } else if (interfaces.isEmpty()) {
            status = Status.WARN;
            summary = "检测到 " + wirelessAdapters + " 块无线网卡，但 netsh 未返回任何无线接口信息";
            diagnosis = "无线网卡存在但无线服务（WlanSvc）可能未运行，或无线功能被硬件开关/飞行模式关闭";
            suggestion = "1.确认“WLAN AutoConfig”服务处于运行状态；2.检查笔记本的无线硬件开关与飞行模式；"
                    + "3.在“设置 → 网络和 Internet → WLAN”中确认无线已打开";
        } else {
            Parsers.WlanInterface wlan = interfaces.get(0);
            int signal = wlan.signalPercent();
            boolean connected = wlan.connected();
            if (!connected) {
                status = anyOtherAdapterUp ? Status.WARN : Status.FAIL;
                summary = "无线接口 " + wlan.name() + " 当前状态为“" + wlan.state() + "”，未连接到任何无线网络"
                        + (anyOtherAdapterUp ? "（但本机存在其他在线网卡）" : "（本机也没有其他在线网卡）");
                diagnosis = anyOtherAdapterUp
                        ? "无线未连接，但有线链路在线，属于预期状态；若你依赖无线，则需要重新连接"
                        : "无线接口未连接，且不存在其他可用链路，本机当前处于断网状态";
                suggestion = "在任务栏网络列表中重新连接目标 Wi-Fi；若看不到 SSID，请确认路由器无线功能已开启且信号覆盖正常";
            } else if (signal >= 0 && signal < 50) {
                status = Status.WARN;
                summary = "已连接 SSID：" + wlan.ssid() + "，信号强度 " + signal + "%（低于 50%），信道 "
                        + wlan.channel() + "，接收/发送速率 " + wlan.receiveRate() + " / " + wlan.transmitRate()
                        + "，BSSID " + wlan.bssid();
                diagnosis = "无线信号强度偏弱，容易出现丢包、掉线或速率下降，是间歇性网络异常的常见原因";
                suggestion = "1.靠近无线路由器或减少中间墙体遮挡；2.在路由器上把信道固定为较空闲的信道（2.4G 建议 1/6/11）；"
                        + "3.若路由器支持，改用 5GHz 频段；4.在设备管理器中确认无线网卡天线连接正常";
            } else {
                status = Status.PASS;
                summary = "已连接 SSID：" + wlan.ssid() + "，信号强度 " + signal + "%，信道 " + wlan.channel()
                        + "，接收/发送速率 " + wlan.receiveRate() + " / " + wlan.transmitRate()
                        + "，认证方式 " + wlan.authentication();
                diagnosis = "无线接口工作正常，链路质量可用";
                suggestion = signal >= 0 && signal < 70
                        ? "信号强度处于中等水平，如遇卡顿可尝试靠近路由器或更换信道"
                        : "无";
            }
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidence("netsh wlan show interfaces 原始输出", wlanRaw)
                .build();
    }
}
