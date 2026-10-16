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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 15：清除 DNS 缓存并重试（只读检查缓存）。
 * <p>
 * 采集：Get-DnsClientCache + ipconfig /displaydns；本工具不执行 flushdns。
 * 判定：缓存中存在异常记录（正常域名被解析到回环/无效地址）→ Warn，建议手动执行 ipconfig /flushdns。
 */
public final class Module15DnsCache extends AbstractCheckModule {

    public Module15DnsCache() {
        super("15", "清除DNS缓存并重试", FaultLayer.DNS_TRANSPORT, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-DnsClientCache 读取 DNS 客户端缓存");
        List<Map<String, Object>> cache = context.psJson(Probes.KEY_DNS_CACHE, Probes.DNS_CACHE);

        context.step("执行 ipconfig /displaydns 采集缓存原始输出");
        CmdResult displayRaw = context.cmd(Probes.KEY_DISPLAY_DNS, Probes.CMDP_DISPLAY_DNS);

        context.step("筛查可疑缓存记录与否定缓存");
        List<String> suspicious = new ArrayList<>();
        int negativeCache = 0;
        for (Map<String, Object> entry : cache) {
            String name = MapAccess.text(entry, "Name");
            String data = MapAccess.text(entry, "Data");
            String type = MapAccess.text(entry, "Type");
            String status = MapAccess.text(entry, "Status");
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (TextUtil.isBlank(data)) {
                negativeCache++;
                continue;
            }
            boolean loopback = data.startsWith("127.") || data.startsWith("0.0.0.0") || data.equals("::1");
            boolean localName = lowerName.endsWith("localhost") || lowerName.endsWith(".local") || lowerName.isEmpty();
            if (loopback && !localName) {
                suspicious.add(name + " → " + data + "（类型 " + type + "）");
            } else if (data.startsWith("0.0.0.0") && !localName) {
                suspicious.add(name + " → " + data + "（广告屏蔽类拦截记录，类型 " + type + "）");
            }
        }

        context.step("判定缓存是否存在异常记录");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (!suspicious.isEmpty()) {
            status = Status.WARN;
            summary = "DNS 缓存中检出 " + suspicious.size() + " 条可疑记录：" + String.join("；", limit(suspicious, 10));
            diagnosis = "缓存中正常域名被解析到回环或无效地址，可能是 hosts/广告拦截、旧缓存残留或 DNS 劫持；"
                    + "若这些域名是你需要访问的站点，会导致“无法打开网页”";
            suggestion = "1.在管理员命令行中手动执行 ipconfig /flushdns 清理缓存后重新访问"
                    + "（本工具只读，不会自动执行该命令）；2.检查 hosts 文件（模块 20）与安全软件的拦截规则；"
                    + "3.若仍被拦截，请核查上游 DNS 是否被劫持";
        } else if (cache.isEmpty()) {
            status = Status.INFO;
            summary = "DNS 客户端缓存为空（可能刚清理过缓存或系统刚启动）";
            diagnosis = "缓存为空时不存在污染风险，本次判定以“缓存内容是否异常”为主，无异常可判";
            suggestion = "无";
        } else {
            status = Status.PASS;
            summary = "DNS 缓存共 " + cache.size() + " 条记录，未发现被劫持或指向回环的异常记录"
                    + (negativeCache > 0 ? "（其中 " + negativeCache + " 条为无数据/否定缓存，属正常现象）" : "");
            diagnosis = "缓存内容与解析结果一致，未发现 DNS 缓存污染迹象";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("DNS 缓存异常筛查结果", "BlameNet 汇总：Get-DnsClientCache 只读读取",
                        "缓存条目总数：" + cache.size() + "\n无数据/否定缓存条目：" + negativeCache
                                + "\n可疑记录：\n" + (suspicious.isEmpty() ? "无" : String.join("\n", suspicious)))
                .evidence("ipconfig /displaydns 原始输出", displayRaw)
                .build();
    }

    private static List<String> limit(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }
}
