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
import com.blamenet.util.TextUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 12：测试到默认网关的连通性。
 * <p>
 * 采集：先取得默认网关，再执行 ping &lt;网关IP&gt; -n 100（次数可通过 --ping-count 调整）。
 * 判定：丢包率 &gt; 5% → Warn；完全不通 → Fail；无有效网关 → Fail。
 */
public final class Module12GatewayConnectivity extends AbstractCheckModule {

    /** 单包等待超时（毫秒）。 */
    private static final int PING_WAIT_MS = 1500;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Module12GatewayConnectivity() {
        super("12", "测试到默认网关的连通性", FaultLayer.LINK, 4, false, 120);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("读取默认网关地址与承载网卡");
        String gateway = NetFacts.defaultGateway(context);
        String adapter = NetFacts.gatewayInterface(context);
        List<Map<String, Object>> gatewayRoutes = new ArrayList<>();
        for (Map<String, Object> route : context.psJson(Probes.KEY_ROUTE_V4, Probes.ROUTE_V4)) {
            if ("0.0.0.0/0".equals(MapAccess.text(route, "DestinationPrefix"))) {
                gatewayRoutes.add(route);
            }
        }

        if (TextUtil.isBlank(gateway)) {
            context.step("未查询到有效网关，采集默认路由作为证据");
            CmdResult routeEvidence = context.ps(Probes.KEY_ROUTE_V4, Probes.ROUTE_V4);
            context.step("记录网关缺失的判定结果");
            context.step("生成结论与修复建议");
            return result()
                    .status(Status.FAIL)
                    .summary("未获取有效网关地址，网关连通测试跳过")
                    .diagnosis("无有效默认网关，无法执行网关连通性检测，根源为 DHCP 分配失败或网关配置缺失")
                    .suggestion("先修复模块 06 / 07 中的地址与网关问题（检查路由器 DHCP 服务、网线与端口），"
                            + "修复后重新运行诊断工具")
                    .evidenceRaw("网关信息采集结果", "Get-NetRoute -AddressFamily IPv4（0.0.0.0/0 默认路由）",
                            gatewayRoutes.isEmpty() ? "未查询到 0.0.0.0/0 默认路由条目" : "查询到默认路由但下一跳无效")
                    .evidence("默认路由原始输出", routeEvidence)
                    .build();
        }

        int pingCount = context.options().effectivePingCount();
        context.step("执行 ping " + gateway + " -n " + pingCount + " 测试网关连通性");
        long timeoutMillis = 30_000L + (long) pingCount * (PING_WAIT_MS + 120L);
        CmdResult pingRaw = context.cmd("pingGateway:" + gateway + ":" + pingCount,
                "ping " + gateway + " -n " + pingCount + " -w " + PING_WAIT_MS, Duration.ofMillis(timeoutMillis));

        context.step("解析丢包率与往返延迟");
        Parsers.PingStats stats = Parsers.parsePing(pingRaw.output());

        context.step("判定网关链路质量");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!stats.reachable()) {
            status = Status.FAIL;
            summary = "到默认网关 " + gateway + " 完全不通（" + pingCount + " 个包全部丢失）"
                    + (stats.fragmentedNeeded() ? "，且出现“需要拆分数据包但设置 DF 标志”" : "");
            diagnosis = "本机到路由器的这一段链路中断：可能是网线/水晶头故障、路由器端口故障、"
                    + "无线信号过弱、网卡驱动异常，或路由器对该网段未转发";
            suggestion = "1.检查网线两端与路由器端口指示灯；2.更换网线或路由器端口后重试；"
                    + "3.无线场景检查信号强度（模块 03）；4.查看模块 04 是否有丢弃计数增长；"
                    + "5.在管理员命令行中手动执行 arp -a 确认网关 MAC 是否存在"
                    + "（本工具只读，不会执行任何修改类命令）";
        } else if (stats.lossPercent() > 5 || stats.lost() > 0) {
            status = Status.WARN;
            summary = "到网关 " + gateway + " 存在丢包：" + stats.summaryText();
            diagnosis = "网关可达但不稳定，丢包常见于网线质量差、无线干扰、网卡节能丢包或路由器负载过高";
            suggestion = "1.更换高质量网线；2.无线场景改用 5GHz 或靠近路由器；"
                    + "3.关闭网卡节能（模块 22、23）；4.登录路由器查看端口错误计数与负载";
        } else {
            status = Status.PASS;
            summary = "到网关 " + gateway + " 连通性正常"
                    + (TextUtil.isNotBlank(adapter) ? "（经网卡 " + adapter + "）" : "") + "：" + stats.summaryText();
            diagnosis = "本机到路由器链路畅通，二层与三层转发正常";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        String recordTime = LocalDateTime.now().format(STAMP);
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("网关连通性判定依据",
                        "ping " + gateway + " -n " + pingCount + " -w " + PING_WAIT_MS,
                        "网关地址：" + gateway + "\n承载网卡：" + (TextUtil.isBlank(adapter) ? "未知" : adapter)
                                + "\n判定时间（本地）：" + recordTime
                                + "\n统计：" + stats.summaryText())
                .evidence("ping 网关原始输出（" + pingCount + " 个包）", pingRaw)
                .build();
    }
}
