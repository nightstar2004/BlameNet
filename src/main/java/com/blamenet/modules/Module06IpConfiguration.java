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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 06：查看完整 IP 配置。
 * <p>
 * 采集：ipconfig /all（原始证据）+ Get-NetIPConfiguration + Get-DnsClientServerAddress（结构化判定）。
 * <p>
 * 判定对象为“主用网卡”（承载默认网关的接口，见 {@link NetFacts#primaryInterface}）：
 * 主用网卡 IPv4 为 169.254.x.x → Fail（DHCP 失败）；主用网卡 IPv4 为 0.0.0.0 → Fail；
 * 主用网卡无默认网关 → Fail；主用网卡无 DNS 服务器 → Warn。
 * 其余 VPN / 虚拟网卡的 169.254 链路本地地址只作为说明写入结论，不参与判定，
 * 避免把正常的虚拟网卡地址误报为整机 IP 层故障。
 */
public final class Module06IpConfiguration extends AbstractCheckModule {

    public Module06IpConfiguration() {
        super("06", "查看完整IP配置", FaultLayer.IP_ROUTING, 5);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 ipconfig /all 采集完整 IP 配置原始输出");
        CmdResult ipconfigRaw = context.cmd(Probes.KEY_IPCONFIG_ALL, Probes.CMDP_IPCONFIG_ALL);

        context.step("执行 Get-NetIPConfiguration 采集结构化 IP 配置");
        List<Map<String, Object>> configs = context.psJson(Probes.KEY_NET_IP_CONFIGURATION, Probes.NET_IP_CONFIGURATION);
        List<Map<String, Object>> dnsRows = context.psJson(Probes.KEY_DNS_CLIENT_SERVER, Probes.DNS_CLIENT_SERVER);

        context.step("解析地址、网关、DNS 与 DHCP 状态");
        List<Parsers.Ipv4Config> parsed = Parsers.parseIpconfig(ipconfigRaw.output());
        NetFacts.PrimaryInterface primary = NetFacts.primaryInterface(context);
        List<String> details = new ArrayList<>();
        List<String> apipaAddresses = new ArrayList<>();
        List<String> zeroAddresses = new ArrayList<>();
        boolean hasDns = false;

        for (Map<String, Object> config : configs) {
            String alias = MapAccess.text(config, "InterfaceAlias");
            List<String> addresses = MapAccess.csv(config, "IPv4Address");
            List<String> prefixes = MapAccess.csv(config, "PrefixLength");
            String gateway = MapAccess.text(config, "DefaultGateway");
            String dns = MapAccess.text(config, "DnsServers");
            String ipv4 = Parsers.firstIpv4(addresses);
            boolean isPrimary = NetFacts.isPrimary(primary, alias);

            details.add("接口 " + alias + "（" + MapAccess.text(config, "InterfaceDescription") + "）："
                    + "IPv4=" + (ipv4 == null ? "无" : ipv4) + "，前缀长度=" + String.join("/", prefixes)
                    + "，默认网关=" + (gateway.isEmpty() ? "无" : gateway)
                    + "，DNS=" + (dns.isEmpty() ? "无" : dns)
                    + "，网络配置文件=" + MapAccess.text(config, "NetProfile")
                    + (isPrimary ? "　[本机主用网卡，作为判定对象]" : ""));

            if (!isPrimary) {
                // 非主用网卡（VPN、Hyper-V、VMware 等虚拟网卡）的链路本地地址只做说明，不参与判定。
                for (String address : addresses) {
                    if (Parsers.isApipa(address)) {
                        apipaAddresses.add(alias + " → " + address);
                    } else if (Parsers.isZeroAddress(address)) {
                        zeroAddresses.add(alias + " → " + address);
                    }
                }
            }
            if (TextUtil.isNotBlank(dns)) {
                hasDns = true;
            }
        }

        for (Map<String, Object> row : dnsRows) {
            if (!MapAccess.stringList(row, "ServerAddresses").isEmpty()) {
                hasDns = true;
            }
        }

        List<String> disconnectedMedia = new ArrayList<>();
        for (Parsers.Ipv4Config config : parsed) {
            if (config.mediaState() != null && config.mediaState().contains("断开")) {
                disconnectedMedia.add(config.adapterName());
            }
        }

        context.step("判定地址有效性与网关、DNS 完整性");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;

        if (configs.isEmpty() || primary == null) {
            status = Status.FAIL;
            summary = "未获取到任何有效的 IPv4 配置";
            diagnosis = "系统未给任何网卡分配 IPv4 地址，可能是网卡未启用、链路未连接、DHCP 无响应或地址被手动清空";
            suggestion = "1.确认网卡已启用且网线/无线已连接；2.检查路由器 DHCP 服务是否开启；"
                    + "3.在管理员命令行中手动执行 ipconfig /renew 重新获取地址（本工具只读，不会自动执行该命令）";
        } else if (primary.ipv4() == null) {
            status = Status.FAIL;
            summary = "主用网卡（" + primary.display() + "）未获取到 IPv4 地址";
            diagnosis = "主用网卡没有 IPv4 地址，无法承载任何 IP 通信；可能是链路未连接、网卡被禁用或地址配置被清空";
            suggestion = "1.确认主用网卡已启用并已连接；2.检查 DHCP 服务是否开启；"
                    + "3.在管理员命令行中手动执行 ipconfig /renew（本工具不会自动执行）";
        } else if (primary.apipa()) {
            status = Status.FAIL;
            summary = "主用网卡（" + primary.display() + "）获取到 APIPA 地址（" + primary.ipv4()
                    + "），未取得有效 DHCP 地址" + (primary.hasGateway() ? "" : "，且无默认网关");
            diagnosis = "DHCP 服务器未响应（或地址池耗尽、VLAN 不匹配、链路中断），主用网卡自动分配 169.254 链路本地地址，"
                    + "该地址无法访问外网，是“网线插着却上不了网”的典型表现";
            suggestion = "1.检查路由器 DHCP 服务是否开启、地址池是否耗尽；2.确认网线插在 LAN 口而非 WAN 口；"
                    + "3.在管理员命令行中手动执行 ipconfig /release 后 ipconfig /renew 重新获取地址"
                    + "（本工具只读，不会自动执行）；4.若为静态 IP 环境，请检查是否缺少手工地址配置";
        } else if (primary.zero()) {
            status = Status.FAIL;
            summary = "主用网卡（" + primary.display() + "）IPv4 地址为 0.0.0.0";
            diagnosis = "0.0.0.0 表示地址尚未完成协商或 DHCP 过程被中断";
            suggestion = "等待 DHCP 完成或在管理员命令行中手动执行 ipconfig /renew（本工具不会自动执行）；"
                    + "若长期为 0.0.0.0，请检查网卡驱动与路由器 DHCP 服务";
        } else if (!primary.hasGateway()) {
            status = Status.FAIL;
            summary = "主用网卡（" + primary.display() + "）已获取 IP 地址 " + primary.ipv4()
                    + "，但缺少有效默认网关，出网流量无出口";
            diagnosis = "网卡拿到地址却没有网关，通常是 DHCP 未下发网关、静态配置遗漏网关，或路由器不在同一网段";
            suggestion = "1.确认路由器配置了网关地址；2.若是静态 IP，请补全默认网关；"
                    + "3.检查本机 IP 与网关是否在同一网段（本工具不会自动修改任何网络配置）";
        } else if (!hasDns) {
            status = Status.WARN;
            summary = "IP 地址与默认网关正常，但未配置任何 DNS 服务器";
            diagnosis = "缺少 DNS 会导致“能 ping 通 IP 但打不开网页”，属于常见配置遗漏";
            suggestion = "在网卡属性中配置 DNS 服务器（如 223.5.5.5 / 119.29.29.29），或让路由器通过 DHCP 下发 DNS；"
                    + "配置完成后可手动执行 ipconfig /flushdns 清理缓存（本工具不会自动执行）";
        } else {
            status = Status.PASS;
            summary = "IP 配置完整：主用网卡 " + primary.display() + " 的地址 " + primary.ipv4()
                    + "、默认网关 " + primary.gateway() + " 与 DNS 均已就绪（共 " + configs.size() + " 个接口）";
            diagnosis = "主用网卡的地址获取、网关与 DNS 配置完整，IP 层配置正常";
            suggestion = "无";
        }

        if (!apipaAddresses.isEmpty()) {
            summary = summary + "；说明：另有 " + apipaAddresses.size()
                    + " 块非主用网卡的链路本地地址（169.254.x.x）属于 VPN / 虚拟网卡正常现象，未计入判定："
                    + String.join("；", apipaAddresses);
        }
        if (!zeroAddresses.isEmpty()) {
            summary = summary + "；说明：非主用网卡存在 0.0.0.0 地址：" + String.join("；", zeroAddresses);
        }
        if (!disconnectedMedia.isEmpty()) {
            summary = summary + "；另有媒体状态为“已断开”的适配器：" + String.join("、", disconnectedMedia);
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("IP 配置结构化盘点", "BlameNet 汇总：Get-NetIPConfiguration + Get-DnsClientServerAddress",
                        String.join("\n", details))
                .evidence("ipconfig /all 原始输出", ipconfigRaw)
                .build();
    }
}
