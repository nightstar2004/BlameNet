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
import com.blamenet.parse.Parsers;
import com.blamenet.util.TextUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模块 07：检查 DHCP 租约状态。
 * <p>
 * 采集：Win32_NetworkAdapterConfiguration（读取租约，不执行 release/renew）+ Get-Service Dhcp。
 * <p>
 * 判定对象为主用网卡：主用网卡地址为 APIPA → Fail；主用网卡租约已过期 → Warn；
 * DHCP 服务未运行 → Fail；主用网卡为静态配置 → Info。
 * 非主用网卡（VPN / Hyper-V / VMware 等）的 169.254 链路本地地址只作说明，不参与判定。
 */
public final class Module07DhcpLease extends AbstractCheckModule {

    private static final DateTimeFormatter LEASE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Module07DhcpLease() {
        super("07", "检查DHCP租约状态", FaultLayer.IP_ROUTING, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-CimInstance 读取网卡 DHCP 租约信息");
        CmdResult configRaw = context.ps(Probes.KEY_DHCP_CONFIG, Probes.DHCP_CONFIG);
        List<Map<String, Object>> configs = context.psJson(Probes.KEY_DHCP_CONFIG, Probes.DHCP_CONFIG);

        context.step("检查 DHCP Client 服务运行状态");
        CmdResult serviceRaw = context.ps(Probes.KEY_DHCP_SERVICE, Probes.DHCP_SERVICE);
        List<Map<String, Object>> services = context.psJson(Probes.KEY_DHCP_SERVICE, Probes.DHCP_SERVICE);
        boolean dhcpServiceRunning = false;
        String dhcpServiceText = "未查询到 Dhcp 服务";
        for (Map<String, Object> service : services) {
            String status = Enums.serviceStatus(MapAccess.text(service, "Status"));
            dhcpServiceText = MapAccess.text(service, "DisplayName") + "（" + MapAccess.text(service, "Name")
                    + "）：状态=" + status + "，启动类型="
                    + Enums.serviceStartType(MapAccess.text(service, "StartType"));
            dhcpServiceRunning = "Running".equalsIgnoreCase(status);
        }

        context.step("解析租约获取时间、过期时间与 DHCP 服务器");
        NetFacts.PrimaryInterface primary = NetFacts.primaryInterface(context);
        List<String> apipaList = new ArrayList<>();
        List<String> apipaOthers = new ArrayList<>();
        List<String> expiredList = new ArrayList<>();
        List<String> staticList = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean primaryDhcpEnabled = false;
        boolean hasEnabledDhcp = false;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime primaryExpiry = null;

        for (Map<String, Object> config : configs) {
            String description = MapAccess.text(config, "Description");
            boolean dhcpEnabled = MapAccess.bool(config, "DHCPEnabled");
            String server = MapAccess.text(config, "DHCPServer");
            List<String> addresses = MapAccess.stringList(config, "IPAddress");
            String obtained = MapAccess.text(config, "DHCPLeaseObtained");
            String expires = MapAccess.text(config, "DHCPLeaseExpires");
            boolean isPrimary = NetFacts.isPrimary(primary, description);

            details.add(description + "：DHCP启用=" + dhcpEnabled + "，DHCP服务器=" + (server.isEmpty() ? "无" : server)
                    + "，地址=" + String.join("、", addresses)
                    + "，租约获取=" + (obtained.isEmpty() ? "无" : obtained)
                    + "，租约过期=" + (expires.isEmpty() ? "无" : expires)
                    + (isPrimary ? "　[本机主用网卡，作为判定对象]" : ""));

            if (dhcpEnabled) {
                hasEnabledDhcp = true;
                if (isPrimary) {
                    primaryDhcpEnabled = true;
                }
            } else if (!addresses.isEmpty() && isPrimary) {
                staticList.add(description);
            }
            for (String address : addresses) {
                if (Parsers.isApipa(address)) {
                    if (isPrimary) {
                        apipaList.add(description + " → " + address);
                    } else {
                        apipaOthers.add(description + " → " + address);
                    }
                }
            }
            LocalDateTime expiry = parseLease(expires);
            if (expiry != null && expiry.isBefore(now) && isPrimary) {
                expiredList.add(description + "（过期时间 " + expires + "）");
            }
            if (isPrimary) {
                primaryExpiry = expiry;
            }
        }

        context.step("按租约状态与 DHCP 服务状态判定");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;

        if (configs.isEmpty()) {
            status = Status.FAIL;
            summary = "未查询到任何已启用 IP 的网卡配置，无法评估 DHCP 租约";
            diagnosis = "系统枚举不到 IPEnabled 的网卡，说明当前没有任何网卡完成 IP 配置";
            suggestion = "确认网卡已启用并已连接后重新运行本工具";
        } else if (!apipaList.isEmpty()) {
            status = Status.FAIL;
            summary = "主用网卡存在 APIPA 链路本地地址，DHCP 租约获取失败：" + String.join("；", apipaList);
            diagnosis = "网卡未能从 DHCP 服务器获得地址（DHCP 服务未响应、地址池耗尽、链路中断或 VLAN 不匹配）"
                    + "，系统退回 169.254.x.x 的自动私有地址";
            suggestion = "1.检查路由器/交换机 DHCP 服务状态；2.确认网线两端连接与端口指示灯；"
                    + "3.在管理员命令行中手动执行 ipconfig /release 与 ipconfig /renew（本工具只读，不会自动执行）";
        } else if (!dhcpServiceRunning) {
            status = Status.FAIL;
            summary = "DHCP Client 服务未运行：" + dhcpServiceText;
            diagnosis = "本机 DHCP 客户端服务未运行时，网卡无法续约或重新获取地址，租约到期后将失去 IP";
            suggestion = "在“服务”管理单元中将“DHCP Client（Dhcp）”服务设为自动启动并启动它"
                    + "（本工具只读，不会修改服务状态）";
        } else if (!expiredList.isEmpty()) {
            status = Status.WARN;
            summary = "主用网卡 DHCP 租约已过期：" + String.join("；", expiredList);
            diagnosis = "租约过期后系统会尝试续约，若续约失败将退化为 APIPA 地址，导致网络中断";
            suggestion = "手动执行 ipconfig /renew 立即续约（本工具不会自动执行），并检查路由器 DHCP 租期设置是否过短";
        } else if (primary != null && !primaryDhcpEnabled && hasEnabledDhcp) {
            status = Status.INFO;
            summary = "主用网卡（" + primary.display() + "）使用静态 IP 配置（未启用 DHCP）"
                    + (staticList.isEmpty() ? "" : "：" + String.join("、", staticList));
            diagnosis = "静态地址环境中不存在 DHCP 租约，本检查项以配置一致性为主";
            suggestion = "确认静态地址、子网掩码、网关与 DNS 与所在网络规划一致；本工具只读，不会修改配置";
        } else if (!hasEnabledDhcp) {
            status = Status.INFO;
            summary = "所有网卡均未启用 DHCP（静态 IP 环境）";
            diagnosis = "静态地址环境中不存在 DHCP 租约，本检查项以配置一致性为主";
            suggestion = "确认静态地址、子网掩码、网关与 DNS 与所在网络规划一致；本工具只读，不会修改配置";
        } else if (primaryExpiry == null) {
            status = Status.INFO;
            summary = "DHCP 已启用且客户端服务运行中，但未返回可解析的租约时间，无法判定剩余租期";
            diagnosis = "系统未返回 DHCPLeaseObtained / DHCPLeaseExpires（常见于地址由路由器长期保留或系统未刷新租约信息）";
            suggestion = "如需确认租期，可在管理员命令行中手动执行 ipconfig /all 查看“获得的租约/租约过期”时间；"
                    + "本工具不会自动执行任何命令";
        } else {
            status = Status.PASS;
            summary = "DHCP 租约正常，客户端服务运行中；主用网卡 " + primary.display()
                    + " 已取得有效租约，过期时间 " + primaryExpiry + "（共盘点 " + details.size() + " 个网卡配置）";
            diagnosis = "DHCP 客户端工作正常，主用网卡的地址租约有效且未过期";
            suggestion = "无";
        }

        if (!apipaOthers.isEmpty()) {
            summary = summary + "；说明：另有 " + apipaOthers.size()
                    + " 块非主用网卡的链路本地地址（169.254.x.x）属于 VPN / 虚拟网卡正常现象，未计入判定："
                    + String.join("；", apipaOthers);
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("DHCP 租约与网卡配置盘点", "BlameNet 汇总：Win32_NetworkAdapterConfiguration 只读读取",
                        String.join("\n", details))
                .evidence("Win32_NetworkAdapterConfiguration 原始输出", configRaw)
                .evidence("DHCP Client 服务状态原始输出", serviceRaw)
                .build();
    }

    private static LocalDateTime parseLease(String value) {
        if (TextUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), LEASE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }
}
