package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.Parsers;

/**
 * 模块 10：测试本地 TCP/IP 协议栈。
 * <p>
 * 采集：ping 127.0.0.1 -n 4
 * 判定：全部超时 → Fail（协议栈损坏）；有丢包 → Warn。
 */
public final class Module10LoopbackStack extends AbstractCheckModule {

    public Module10LoopbackStack() {
        super("10", "测试本地TCP/IP协议栈", FaultLayer.LINK, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 ping 127.0.0.1 -n 4 测试回环接口");
        CmdResult pingRaw = context.cmd("pingLoopback", Probes.CMDP_PING_LOOPBACK);

        context.step("解析丢包率与延迟");
        Parsers.PingStats stats = Parsers.parsePing(pingRaw.output());

        context.step("判定协议栈是否正常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!stats.reachable()) {
            status = Status.FAIL;
            summary = "本机回环地址 127.0.0.1 不可达，TCP/IP 协议栈异常";
            diagnosis = "回环接口不响应说明 Winsock/TCPIP 协议栈被破坏、回环接口被禁用，或安全软件拦截了本地回环流量";
            suggestion = "1.在管理员命令行中执行 netsh winsock reset 与 netsh int ip reset 后重启"
                    + "（本工具只读，不会自动执行）；2.检查安全软件是否禁用了本地回环；"
                    + "3.若问题持续，考虑系统还原或重置网络组件";
        } else if (stats.lost() > 0) {
            status = Status.WARN;
            summary = "回环地址可达，但存在丢包：" + stats.summaryText();
            diagnosis = "本机回环出现丢包，通常与协议栈组件损坏、驱动冲突或安全软件过滤有关";
            suggestion = "建议在管理员命令行中执行 netsh int ip reset 后重启（本工具不会自动执行）；"
                    + "并临时关闭安全软件的防护对比测试";
        } else {
            status = Status.PASS;
            summary = "本机回环地址 127.0.0.1 通信正常：" + stats.summaryText();
            diagnosis = "本机 TCP/IP 协议栈工作正常，问题不在协议栈本身";
            suggestion = "无";
        }

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidence("ping 127.0.0.1 -n 4 原始输出", pingRaw)
                .build();
    }
}
