package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.Parsers;
import com.blamenet.util.TextUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块 28：检查 MTU 与分片问题。
 * <p>
 * 采集：以“禁止分片（-f）”方式逐级 ping &lt;目标&gt; -l &lt;size&gt;，从 1472 递减到 1200，
 * 定位路径上可通过的最大载荷；找到最大可通尺寸后即停止，避免无谓的等待。
 * 判定：小包通、大包不通 → Fail（路径 MTU 问题）；全部可通 → Pass。
 */
public final class Module28MtuFragmentation extends AbstractCheckModule {

    /** 探测起始载荷（1472 + 28 = 1500）。 */
    private static final int START_SIZE = 1472;

    /** 探测终止载荷。 */
    private static final int END_SIZE = 1200;

    /** 递减步长。 */
    private static final int STEP = 8;

    /** 单包等待超时（毫秒）。 */
    private static final int WAIT_MS = 1500;

    public Module28MtuFragmentation() {
        super("28", "检查MTU与分片问题", FaultLayer.DEEP_PACKET, 8, false, 90);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("确定 MTU 探测目标（优先默认网关，其次 8.8.8.8）");
        String gateway = NetFacts.defaultGateway(context);
        String target = TextUtil.isNotBlank(gateway) ? gateway : "8.8.8.8";
        boolean gatewayReachable = context.statusOf("12") == Status.PASS || context.statusOf("12") == Status.WARN;
        if (!gatewayReachable && TextUtil.isNotBlank(gateway)) {
            target = gateway;
        }

        context.step("从 " + START_SIZE + " 字节开始逐级探测（禁止分片）");
        List<Attempt> attempts = new ArrayList<>();
        int largestWorking = -1;
        boolean anyFragmentedNeeded = false;
        boolean anyTimeout = false;

        for (int size = START_SIZE; size >= END_SIZE; size -= STEP) {
            CmdResult ping = context.cmdNoCache(
                    "ping " + target + " -f -l " + size + " -n 1 -w " + WAIT_MS,
                    Duration.ofMillis(WAIT_MS + 5000L));
            Parsers.PingStats stats = Parsers.parsePing(ping.output());
            boolean success = stats.reachable();
            anyFragmentedNeeded |= stats.fragmentedNeeded();
            anyTimeout |= stats.timedOut() || (!success && !stats.fragmentedNeeded());
            attempts.add(new Attempt(size, success, stats.fragmentedNeeded(), stats.timedOut(), ping.output()));
            if (success) {
                largestWorking = size;
                break;
            }
        }
        context.step("共执行 " + attempts.size() + " 次带 DF 标志的 ping 探测");

        context.step("分析分片与路径 MTU");
        int pathMtu = largestWorking > 0 ? largestWorking + 28 : -1;
        boolean allFailed = largestWorking < 0;

        context.step("判定是否存在 MTU 受限问题");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (allFailed && anyFragmentedNeeded) {
            status = Status.FAIL;
            summary = "目标 " + target + " 在 " + START_SIZE + " 到 " + END_SIZE
                    + " 字节的全部载荷下均提示“需要拆分数据包但已设置 DF 标志”，路径 MTU 低于 " + (END_SIZE + 28) + " 字节";
            diagnosis = "路径 MTU 异常偏小，常见于 PPPoE、隧道/VPN、某些企业网关的 MTU 限制；"
                    + "表现为能 ping 通小包、能打开部分网站，但大页面/文件传输/HTTPS 握手失败";
            suggestion = "1.确认是否使用了 PPPoE 拨号或 VPN/隧道（这类链路 MTU 通常为 1492 或更低）；"
                    + "2.在路由器 WAN 口把 MTU 手动设为 1492 或 1400 后重测；"
                    + "3.不要把本机网卡 MTU 调得比实际路径更大（本工具只读，不会修改任何 MTU 设置）";
        } else if (allFailed) {
            status = Status.INFO;
            summary = "目标 " + target + " 在所有探测尺寸下均不可达（超时或无响应），无法判定 MTU";
            diagnosis = "目标地址本身不可达时无法测量路径 MTU；请先修复模块 12（网关连通性）后再评估 MTU";
            suggestion = "先解决网关/链路连通性问题，然后重新运行本工具进行 MTU 探测";
        } else if (largestWorking < START_SIZE) {
            status = Status.FAIL;
            summary = "路径 MTU 受限：载荷 " + START_SIZE + " 字节不可通，最大可通载荷为 " + largestWorking
                    + " 字节（对应路径 MTU " + pathMtu + " 字节，标准以太网为 1500）";
            diagnosis = "路径 MTU 小于标准 1500 字节，超过该尺寸的数据包会被路径上的设备丢弃；"
                    + "典型症状是“ping 通但打不开网页 / 图片加载不全 / 大文件传输失败”；"
                    + "本机网卡 MTU 与路径不匹配时还会触发 PMTUD 黑洞";
            suggestion = "1.确认链路类型（PPPoE 通常 1492、隧道常见 1400-1460）；"
                    + "2.在路由器或本机网卡上把 MTU 调整为不超过 " + pathMtu + " 字节；"
                    + "3.若 ICMP 被阻断导致 PMTUD 失败，请在防火墙放行必要的 ICMP（本工具只读，不会修改任何配置）";
        } else {
            status = Status.PASS;
            summary = "路径 MTU 正常：载荷 " + START_SIZE + " 字节（合计 " + (START_SIZE + 28)
                    + " 字节）可正常通过，目标 " + target;
            diagnosis = "路径 MTU 为标准的 1500 字节，不存在分片与 MTU 黑洞问题";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        List<String> table = new ArrayList<>();
        for (Attempt attempt : attempts) {
            table.add("载荷 " + attempt.size() + " 字节：" + (attempt.success() ? "成功"
                    : attempt.fragmentedNeeded() ? "需要拆分数据包但已设置 DF 标志"
                    : attempt.timedOut() ? "请求超时" : "失败"));
        }

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("MTU 逐级探测结果", "ping " + target + " -f -l <" + START_SIZE + ".." + END_SIZE
                                + " 步长 " + STEP + "> -n 1 -w " + WAIT_MS,
                        String.join("\n", table) + "\n\n最大可通载荷：" + (largestWorking < 0 ? "无" : largestWorking + " 字节")
                                + "\n推导路径 MTU：" + (pathMtu < 0 ? "无法判定" : pathMtu + " 字节"))
                .evidenceRaw("MTU 探测原始输出（全部探测记录）",
                        "ping " + target + " -f -l <size> -n 1 -w " + WAIT_MS + "（共 " + attempts.size() + " 次）",
                        rawOutputs(attempts))
                .build();
    }

    private static String rawOutputs(List<Attempt> attempts) {
        StringBuilder sb = new StringBuilder();
        for (Attempt attempt : attempts) {
            sb.append("===== 载荷 ").append(attempt.size()).append(" 字节 =====\n")
                    .append(attempt.rawOutput()).append('\n');
        }
        return sb.toString();
    }

    /** 单次探测记录。 */
    private record Attempt(int size, boolean success, boolean fragmentedNeeded, boolean timedOut, String rawOutput) {
    }
}
