package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Probes;
import com.blamenet.core.AbstractCheckModule;
import com.blamenet.core.DiagnosticContext;
import com.blamenet.model.FaultLayer;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.parse.MapAccess;
import com.blamenet.util.TextUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 14：测试 DNS 解析。
 * <p>
 * 采集：Resolve-DnsName www.baidu.com（记录真实耗时）+ nslookup www.baidu.com（原始证据）。
 * 判定：解析失败 → Fail；解析慢（&gt; 2s）→ Warn。
 */
public final class Module14DnsResolution extends AbstractCheckModule {

    /** 解析耗时告警阈值（毫秒）。 */
    private static final long SLOW_THRESHOLD_MS = 2000L;

    public Module14DnsResolution() {
        super("14", "测试DNS解析", FaultLayer.DNS_TRANSPORT, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Resolve-DnsName www.baidu.com 解析域名");
        CmdResult resolveRaw = context.ps(Probes.KEY_DNS_RESOLVE, Probes.DNS_RESOLVE, Duration.ofSeconds(60));
        List<Map<String, Object>> resolved = context.psJson(Probes.KEY_DNS_RESOLVE, Probes.DNS_RESOLVE);

        context.step("执行 nslookup www.baidu.com 采集原始证据");
        CmdResult nslookupRaw = context.cmd(Probes.KEY_NSLOOKUP, Probes.CMDP_NSLOOKUP, Duration.ofSeconds(60));

        context.step("汇总解析结果与耗时");
        List<String> addresses = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (Map<String, Object> record : resolved) {
            String ip = MapAccess.text(record, "IPAddress");
            if (TextUtil.isNotBlank(ip)) {
                addresses.add(ip);
            }
            details.add("名称=" + MapAccess.text(record, "Name") + " 类型=" + MapAccess.text(record, "Type")
                    + " 地址=" + (ip.isEmpty() ? "-" : ip) + " 别名=" + MapAccess.text(record, "NameHost")
                    + " TTL=" + MapAccess.text(record, "TTL"));
        }
        long cost = resolveRaw.durationMillis();
        List<String> dnsServers = new ArrayList<>(NetFacts.dnsServers(context).values().stream()
                .flatMap(List::stream).toList());

        context.step("判定解析是否成功与是否过慢");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (addresses.isEmpty()) {
            status = Status.FAIL;
            summary = "DNS 解析失败：无法将 www.baidu.com 解析为 IP 地址（耗时 " + cost + " ms）";
            diagnosis = "域名解析失败说明 DNS 链路不可用：可能是 DNS 服务器不可达、UDP/TCP 53 端口被拦截、"
                    + "路由器未下发 DNS，或本机 DNS 客户端服务异常";
            suggestion = "1.检查模块 16 / 24 中的 DNS 客户端服务状态；2.临时更换 DNS 服务器为 223.5.5.5 / 119.29.29.29 后重试；"
                    + "3.在管理员命令行中手动执行 ipconfig /flushdns 清理缓存（本工具只读，不会自动执行）；"
                    + "4.结合模块 17 判断 53 端口是否被安全软件拦截";
        } else if (cost > SLOW_THRESHOLD_MS) {
            status = Status.WARN;
            summary = "DNS 解析成功但耗时较长（" + cost + " ms，超过 " + SLOW_THRESHOLD_MS + " ms 阈值）："
                    + String.join("、", addresses);
            diagnosis = "解析偏慢通常由 DNS 服务器响应慢、UDP 丢包重传或 DNS 缓存被污染导致，表现为打开网页时有明显首包延迟";
            suggestion = "1.更换为响应更快的公共 DNS；2.清理本机 DNS 缓存（手动执行 ipconfig /flushdns）；"
                    + "3.检查模块 14 的丢包情况与模块 12 的网关质量";
        } else {
            status = Status.PASS;
            summary = "DNS 解析正常，www.baidu.com → " + String.join("、", addresses) + "（耗时 " + cost + " ms）";
            diagnosis = "域名解析链路工作正常，解析耗时可接受";
            suggestion = "无";
        }

        if (!dnsServers.isEmpty()) {
            summary = summary + "；当前 DNS 服务器：" + String.join("、", dnsServers);
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("DNS 解析记录盘点", "BlameNet 汇总：Resolve-DnsName www.baidu.com",
                        (details.isEmpty() ? "未返回任何解析记录" : String.join("\n", details))
                                + "\n解析真实耗时：" + cost + " ms")
                .evidence("Resolve-DnsName 原始输出", resolveRaw)
                .evidence("nslookup 原始输出", nslookupRaw)
                .build();
    }
}
