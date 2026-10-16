package com.blamenet.modules;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.NetTest;
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
 * 模块 19：检查代理设置。
 * <p>
 * 采集：netsh winhttp show proxy（原始证据）+ 注册表 Internet Settings（结构化）。
 * 判定：配置了代理但不可达 → Fail（通过真实 TCP 连接验证）；自动检测开启但 PAC 不可访问 → Warn。
 */
public final class Module19ProxySettings extends AbstractCheckModule {

    private static final int CONNECT_TIMEOUT_MS = 3000;

    public Module19ProxySettings() {
        super("19", "检查代理设置", FaultLayer.SECURITY_PROXY, 5);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 netsh winhttp show proxy 采集系统代理配置");
        CmdResult winhttpRaw = context.cmd(Probes.KEY_WINHTTP_PROXY, Probes.CMDP_WINHTTP_PROXY);

        context.step("读取注册表 Internet Settings 代理项");
        CmdResult ieRaw = context.ps(Probes.KEY_IE_PROXY, Probes.IE_PROXY);
        Map<String, Object> ie = context.psJsonFirst(Probes.KEY_IE_PROXY, Probes.IE_PROXY);

        boolean ieProxyEnabled = MapAccess.number(ie, "ProxyEnable", 0) == 1;
        String ieProxyServer = MapAccess.text(ie, "ProxyServer");
        String proxyOverride = MapAccess.text(ie, "ProxyOverride");
        String autoConfigUrl = MapAccess.text(ie, "AutoConfigURL");
        boolean autoDetect = MapAccess.number(ie, "AutoDetect", 0) == 1;

        String winhttpProxy = parseWinhttpProxy(winhttpRaw.output());

        context.step("对已配置的代理服务器执行真实 TCP 可达性验证");
        List<String> checked = new ArrayList<>();
        boolean proxyUnreachable = false;
        boolean pacUnreachable = false;

        if (TextUtil.isNotBlank(winhttpProxy)) {
            NetTest.TcpConnectResult result = connect(winhttpProxy);
            checked.add("[WinHTTP 代理] " + result.rawOutput());
            proxyUnreachable |= !result.connected();
        }
        if (ieProxyEnabled && TextUtil.isNotBlank(ieProxyServer)) {
            for (String server : ieProxyServer.split("[;]")) {
                if (TextUtil.isBlank(server)) {
                    continue;
                }
                NetTest.TcpConnectResult result = connect(server.trim());
                checked.add("[IE/系统代理] " + result.rawOutput());
                proxyUnreachable |= !result.connected();
            }
        }
        if (TextUtil.isNotBlank(autoConfigUrl)) {
            String hostPort = hostPortOf(autoConfigUrl);
            if (hostPort != null) {
                NetTest.TcpConnectResult result = connect(hostPort);
                checked.add("[PAC 自动配置脚本] " + result.rawOutput());
                pacUnreachable = !result.connected();
            }
        }

        context.step("判定代理配置是否造成访问异常");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        boolean anyProxyConfigured = TextUtil.isNotBlank(winhttpProxy)
                || (ieProxyEnabled && TextUtil.isNotBlank(ieProxyServer));

        if (anyProxyConfigured && proxyUnreachable) {
            status = Status.FAIL;
            summary = "已配置代理但代理服务器不可达："
                    + (TextUtil.isNotBlank(winhttpProxy) ? "WinHTTP=" + winhttpProxy + "；" : "")
                    + (ieProxyEnabled && TextUtil.isNotBlank(ieProxyServer) ? "系统代理=" + ieProxyServer : "");
            diagnosis = "代理服务器不可达会造成“浏览器打不开网页但 ping 正常”的典型现象：所有应用流量被发往一个不存在的代理";
            suggestion = "1.确认代理软件（如 Clash/v2ray/企业代理客户端）是否正在运行；"
                    + "2.如已不再使用代理，请在“设置 → 网络和 Internet → 代理”中关闭手动代理，"
                    + "并执行 netsh winhttp reset proxy 重置 WinHTTP 代理（本工具只读，不会自动执行）；"
                    + "3.检查代理的绕过列表是否把需要的域名误加入直连";
        } else if (pacUnreachable) {
            status = Status.WARN;
            summary = "存在自动配置脚本（PAC）且脚本地址不可访问：" + autoConfigUrl;
            diagnosis = "PAC 脚本不可达时浏览器会回退为直连或长时间等待，导致访问缓慢、部分站点打不开";
            suggestion = "确认 PAC 脚本服务器是否在线；如不需要自动配置，请在代理设置中关闭“使用自动配置脚本”"
                    + "（本工具只读，不会修改注册表）";
        } else if (anyProxyConfigured) {
            status = Status.PASS;
            summary = "已配置代理且代理服务器可达："
                    + (TextUtil.isNotBlank(winhttpProxy) ? "WinHTTP=" + winhttpProxy + "；" : "")
                    + (ieProxyEnabled && TextUtil.isNotBlank(ieProxyServer) ? "系统代理=" + ieProxyServer : "");
            diagnosis = "代理配置有效，代理链路可达，不会造成访问异常";
            suggestion = "若访问某些站点异常，请检查代理规则（分流/绕过列表）是否覆盖该站点";
        } else if (autoDetect) {
            status = Status.INFO;
            summary = "未配置固定代理，但已启用“自动检测设置”";
            diagnosis = "自动检测（WPAD）会向网络请求代理发现信息；若网络中不存在 WPAD 服务，可能造成启动时的额外延迟";
            suggestion = "若不需要企业自动代理发现，可关闭“自动检测设置”以略微加快网页首包速度";
        } else {
            status = Status.PASS;
            summary = "未配置任何代理（WinHTTP 与系统代理均为直连）";
            diagnosis = "不存在代理配置，排除代理造成的访问异常";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("代理可达性真实连接测试", "BlameNet 内置只读 TCP 连接测试（超时 " + CONNECT_TIMEOUT_MS + " ms）",
                        checked.isEmpty() ? "本次未检测到任何需要验证的代理服务器" : String.join("\n\n", checked))
                .evidence("netsh winhttp show proxy 原始输出", winhttpRaw)
                .evidenceRaw("注册表代理项（HKCU Internet Settings）", "BlameNet 汇总：Get-ItemProperty 只读读取",
                        "ProxyEnable=" + (ieProxyEnabled ? 1 : 0) + "\nProxyServer=" + TextUtil.nvl(ieProxyServer)
                                + "\nProxyOverride=" + TextUtil.nvl(proxyOverride)
                                + "\nAutoConfigURL=" + TextUtil.nvl(autoConfigUrl)
                                + "\nAutoDetect=" + (autoDetect ? 1 : 0))
                .evidence("注册表读取原始输出", ieRaw)
                .build();
    }

    /** 从 netsh winhttp show proxy 输出中提取代理地址；直连返回空串。 */
    private static String parseWinhttpProxy(String output) {
        String text = TextUtil.normalize(output);
        if (text.contains("直接访问") || text.toLowerCase(Locale.ROOT).contains("direct access")) {
            return "";
        }
        for (String line : text.split("\n")) {
            String[] pair = com.blamenet.parse.TextTable.splitKeyValue(line);
            if (pair == null) {
                continue;
            }
            String key = com.blamenet.parse.TextTable.normalizeKey(pair[0]).toLowerCase(Locale.ROOT);
            String value = pair[1].trim();
            if (key.contains("代理服务器") || key.contains("proxy server")) {
                if (TextUtil.isNotBlank(value) && !value.contains("直接访问")) {
                    return value;
                }
            }
        }
        return "";
    }

    private static NetTest.TcpConnectResult connect(String hostPort) {
        String[] parts = splitHostPort(hostPort);
        return NetTest.tcpConnect(parts[0], (int) TextUtil.parseLong(parts[1], 80), CONNECT_TIMEOUT_MS);
    }

    /** 拆分 host:port，支持 http:// 前缀与省略端口。 */
    private static String[] splitHostPort(String value) {
        String text = TextUtil.nvl(value).trim();
        int scheme = text.indexOf("://");
        if (scheme >= 0) {
            text = text.substring(scheme + 3);
        }
        int slash = text.indexOf('/');
        if (slash > 0) {
            text = text.substring(0, slash);
        }
        String host = text;
        String port = "80";
        int colon = text.lastIndexOf(':');
        if (colon > 0 && colon < text.length() - 1) {
            host = text.substring(0, colon);
            port = text.substring(colon + 1);
        }
        return new String[]{host, port};
    }

    private static String hostPortOf(String url) {
        if (TextUtil.isBlank(url)) {
            return null;
        }
        String[] parts = splitHostPort(url);
        return parts[0] + ":" + parts[1];
    }
}
