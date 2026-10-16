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

import java.time.Duration;

/**
 * 模块 17：测试远程 TCP 端口可达性。
 * <p>
 * 采集：Test-NetConnection www.baidu.com -Port 443 -InformationLevel Detailed
 * 判定：TcpTestSucceeded = False → Fail；PingSucceeded = False 但 TCP 成功 → Info（ICMP 被拦截）。
 */
public final class Module17RemoteTcpPort extends AbstractCheckModule {

    private static final String TARGET = "www.baidu.com";
    private static final int PORT = 443;

    public Module17RemoteTcpPort() {
        super("17", "测试远程TCP端口可达性", FaultLayer.DNS_TRANSPORT, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Test-NetConnection " + TARGET + " -Port " + PORT);
        CmdResult raw = context.ps(Probes.KEY_TEST_NET_CONNECTION, Probes.TEST_NET_CONNECTION, Duration.ofSeconds(90));

        context.step("解析 TCP 与 ICMP 测试结果");
        Parsers.TcpTest test = Parsers.parseTestNetConnection(raw.output(), TARGET, PORT);

        context.step("判定远程端口可达性");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!test.parsed()) {
            status = Status.INFO;
            summary = "未能解析 Test-NetConnection 的输出，无法判定远程端口可达性";
            diagnosis = "命令输出格式与预期不一致，可能系统未提供 Test-NetConnection 或输出被本地化改写，"
                    + "原始输出已完整保留在证据中供人工核对";
            suggestion = "可在 PowerShell 中手动执行 Test-NetConnection " + TARGET + " -Port " + PORT
                    + " -InformationLevel Detailed 核对";
        } else if (!test.tcpSucceeded()) {
            status = Status.FAIL;
            summary = "远程 TCP 端口不可达：" + TARGET + ":" + PORT + "（TCP 连接失败"
                    + (TextUtil.isNotBlank(test.remoteAddress()) ? "，解析地址 " + test.remoteAddress() : "") + "）";
            diagnosis = "能解析地址但 TCP 端口不通，通常是出口被安全软件/防火墙阻断、代理配置错误，"
                    + "或链路中断；如果 ping 也不通，需要结合模块 12 判断链路状态";
            suggestion = "1.检查模块 18 / 19 中的防火墙出站规则与代理设置；"
                    + "2.临时关闭安全软件的网络防护对比测试；"
                    + "3.确认目标端口未被运营商或企业网关封禁（本工具只读，不会修改任何策略）";
        } else if (!test.pingSucceeded()) {
            status = Status.INFO;
            summary = "TCP 端口可达（" + TARGET + ":" + PORT + " 三次握手成功"
                    + (TextUtil.isNotBlank(test.rtt()) ? "，RTT " + test.rtt() : "") + "），但 ICMP ping 不通";
            diagnosis = "仅 ICMP 被拦截属于常见现象（目标或中间设备丢弃 ICMP），不影响 TCP 业务访问，因此不判为故障";
            suggestion = "无需处理；后续判断链路质量请以 TCP 测试与模块 12 的网关连通性为准";
        } else {
            status = Status.PASS;
            summary = "远程 TCP 端口可达：" + TARGET + ":" + PORT + " 连接成功"
                    + (TextUtil.isNotBlank(test.rtt()) ? "，RTT " + test.rtt() : "")
                    + (TextUtil.isNotBlank(test.remoteAddress()) ? "，远端地址 " + test.remoteAddress() : "");
            diagnosis = "本机到公网 443 端口连通，说明出口链路与安全策略均放行常规 HTTPS 流量";
            suggestion = "无";
        }

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("TCP 可达性判定依据", "Test-NetConnection " + TARGET + " -Port " + PORT,
                        "TcpTestSucceeded=" + test.tcpSucceeded() + "\nPingSucceeded=" + test.pingSucceeded()
                                + "\nRemoteAddress=" + test.remoteAddress() + "\nSourceAddress=" + test.sourceAddress()
                                + "\nPingReplyDetails=" + (test.rtt().isEmpty() ? "无" : test.rtt()))
                .evidence("Test-NetConnection 原始输出", raw)
                .build();
    }
}
