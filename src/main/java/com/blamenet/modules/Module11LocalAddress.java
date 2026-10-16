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

/**
 * 模块 11：测试本机 IP 地址。
 * <p>
 * 采集：先获取本机 IPv4，再执行 ping &lt;本机IP&gt; -n 4。
 * 判定：不通 → Fail（网卡绑定异常）；通 → Pass。
 */
public final class Module11LocalAddress extends AbstractCheckModule {

    public Module11LocalAddress() {
        super("11", "测试本机IP地址", FaultLayer.LINK, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("读取本机 IPv4 地址");
        String localIp = NetFacts.localIpv4(context);

        context.step("执行 ping 本机地址测试网卡绑定");
        CmdResult pingRaw;
        Parsers.PingStats stats;
        if (TextUtil.isBlank(localIp)) {
            pingRaw = CmdResult.notExecuted("ping <本机IP> -n 4", "本机没有可用的 IPv4 地址，无法执行本机地址连通性测试");
            stats = Parsers.parsePing("");
        } else {
            pingRaw = context.cmd("pingSelf:" + localIp, "ping " + localIp + " -n 4", Duration.ofSeconds(60));
            stats = Parsers.parsePing(pingRaw.output());
        }

        context.step("判定网卡地址绑定是否正常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (TextUtil.isBlank(localIp)) {
            status = Status.FAIL;
            summary = "未获取到本机 IPv4 地址，本机地址连通性测试无法执行";
            diagnosis = "网卡没有 IPv4 地址（未获得 DHCP 地址或未配置静态地址），因此无法验证地址绑定";
            suggestion = "先修复模块 06 / 07 中的 IP 配置问题，再重新运行本工具";
        } else if (!stats.reachable()) {
            status = Status.FAIL;
            summary = "本机地址 " + localIp + " 无法 ping 通自身，网卡地址绑定异常";
            diagnosis = "地址已配置到网卡但本地无响应，常见于驱动异常、TCP/IP 协议栈损坏或安全软件拦截 ICMP";
            suggestion = "1.在“设备管理器”中禁用再启用网卡；2.在管理员命令行中执行 netsh int ip reset 后重启"
                    + "（本工具只读，不会自动执行）；3.检查安全软件是否开启“禁止 ping”类防护";
        } else if (stats.lost() > 0) {
            status = Status.WARN;
            summary = "本机地址 " + localIp + " 可 ping 通但有丢包：" + stats.summaryText();
            diagnosis = "本机地址自身出现丢包，指向网卡驱动或协议栈不稳定";
            suggestion = "更新网卡驱动并检查网卡节能设置（见模块 22、23）";
        } else {
            status = Status.PASS;
            summary = "本机地址 " + localIp + " 通信正常：" + stats.summaryText();
            diagnosis = "网卡地址绑定与协议栈工作正常";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidence("ping 本机地址原始输出", pingRaw)
                .build();
    }
}
