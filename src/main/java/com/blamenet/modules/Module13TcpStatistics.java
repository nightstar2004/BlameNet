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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块 13：检查 TCP/IP 连接统计。
 * <p>
 * 采集：Get-NetTCPConnection 状态分组 + netstat -ano（原始证据）+ 连接数最高的进程名。
 * 判定：大量 SYN_SENT → Warn（连接建立失败）；异常进程占用大量连接 → Warn。
 */
public final class Module13TcpStatistics extends AbstractCheckModule {

    /** SYN_SENT 数量的告警阈值。 */
    private static final int SYN_SENT_THRESHOLD = 20;

    /** 单进程连接数的告警阈值。 */
    private static final int PROCESS_CONNECTION_THRESHOLD = 200;

    public Module13TcpStatistics() {
        super("13", "检查TCP/IP连接统计", FaultLayer.LINK, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetTCPConnection 采集全部 TCP 连接");
        List<Map<String, Object>> connections = context.psJson(Probes.KEY_TCP_CONNECTIONS, Probes.TCP_CONNECTIONS);

        context.step("执行 netstat -ano 采集连接明细");
        CmdResult netstatRaw = context.cmd(Probes.KEY_NETSTAT_ANO, Probes.CMDP_NETSTAT_ANO);

        context.step("按状态与进程聚合连接数量");
        Map<String, Integer> byState = new LinkedHashMap<>();
        Map<Long, Integer> byProcess = new LinkedHashMap<>();
        for (Map<String, Object> connection : connections) {
            String state = Enums.tcpState(MapAccess.text(connection, "State"));
            byState.merge(state, 1, Integer::sum);
            long pid = MapAccess.number(connection, "OwningProcess", -1);
            if (pid > 0) {
                byProcess.merge(pid, 1, Integer::sum);
            }
        }
        List<Map.Entry<Long, Integer>> topProcesses = byProcess.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .toList();

        List<String> processNames = new ArrayList<>();
        if (!topProcesses.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Map.Entry<Long, Integer> entry : topProcesses) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(entry.getKey());
            }
            List<Map<String, Object>> processes = context.psJson("tcpTopProcessesByPid",
                    "@(Get-Process -Id " + ids + " -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,Path) "
                            + "| ConvertTo-Json -Depth 4", Duration.ofSeconds(30));
            Map<Long, String> nameById = new LinkedHashMap<>();
            for (Map<String, Object> process : processes) {
                nameById.put(MapAccess.number(process, "Id", -1),
                        MapAccess.text(process, "ProcessName") + "（" + MapAccess.text(process, "Path") + "）");
            }
            for (Map.Entry<Long, Integer> entry : topProcesses) {
                processNames.add(entry.getKey() + " " + nameById.getOrDefault(entry.getKey(), "未知进程")
                        + " → " + entry.getValue() + " 条连接");
            }
        }

        int synSent = byState.getOrDefault("SynSent", 0) + byState.getOrDefault("SYN_SENT", 0);
        long maxProcessConnections = byProcess.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        context.step("判定连接状态异常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        StringBuilder stateText = new StringBuilder();
        for (Map.Entry<String, Integer> entry : byState.entrySet()) {
            if (stateText.length() > 0) {
                stateText.append("、");
            }
            stateText.append(entry.getKey()).append('=').append(entry.getValue());
        }

        if (connections.isEmpty()) {
            status = Status.INFO;
            summary = "当前没有采集到 TCP 连接（无活动网络会话）";
            diagnosis = "系统当前没有 TCP 连接，说明没有应用正在使用网络；若你正在使用浏览器或下载工具，则需要复核采集结果";
            suggestion = "无";
        } else if (synSent > SYN_SENT_THRESHOLD) {
            status = Status.WARN;
            summary = "存在大量 SYN_SENT 连接（" + synSent + " 条），连接建立可能失败；总连接数 "
                    + connections.size() + "（" + stateText + "）";
            diagnosis = "大量半开连接通常表示目标端口不通、被防火墙丢弃，或本机存在异常程序在做高频连接尝试";
            suggestion = "1.结合模块 17 判断远程端口是否可达；2.用资源监视器查看是哪一进程发起连接；"
                    + "3.如为企业环境请排查安全软件/防火墙策略（本工具只读，不会终止任何进程）";
        } else if (maxProcessConnections > PROCESS_CONNECTION_THRESHOLD) {
            status = Status.WARN;
            summary = "存在单个进程占用大量连接（最多 " + maxProcessConnections + " 条）："
                    + String.join("；", processNames);
            diagnosis = "单进程连接数过高可能是下载/P2P/代理软件或异常程序造成的连接堆积，会耗尽本地端口或路由器会话表";
            suggestion = "1.确认该进程是否为预期的下载或同步工具；2.若为异常进程，请排查其来源；"
                    + "3.必要时在应用内限制并发连接数（本工具只读，不会终止任何进程）";
        } else {
            status = Status.PASS;
            summary = "TCP 连接状态分布正常：共 " + connections.size() + " 条（" + stateText + "）"
                    + (processNames.isEmpty() ? "" : "；连接最多的进程：" + processNames.get(0));
            diagnosis = "连接状态以 Established 与 TimeWait 为主，未见连接建立失败的堆积现象";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("TCP 连接聚合统计", "BlameNet 汇总：Get-NetTCPConnection 按状态与进程聚合",
                        "状态分布：" + stateText + "\n连接数最多的进程：\n"
                                + (processNames.isEmpty() ? "无" : String.join("\n", processNames)))
                .evidence("netstat -ano 原始输出", netstatRaw)
                .build();
    }
}
