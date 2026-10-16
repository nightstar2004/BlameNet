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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块 16：检查 DNS 客户端服务。
 * <p>
 * 采集：Get-Service Dnscache, Dhcp, Netman, NlaSvc, WlanSvc
 * 判定：任一服务未运行 → Fail；启动类型非自动 → Warn。
 * <p>
 * 唯一的适用性例外：WlanSvc 依赖无线网卡硬件。本机不存在无线网卡时（依据
 * Get-NetAdapter 的真实网卡列表判定），该服务不参与判定，只在证据中如实记录。
 */
public final class Module16DnsClientService extends AbstractCheckModule {

    /** 各服务停止后的实际影响；仅用于生成精确诊断，不改变判定规则。 */
    private static final Map<String, String> SERVICE_IMPACT = Map.of(
            "Dnscache", "域名解析能力受损，缓存内已有记录仍可命中，缓存过期后无法解析新域名",
            "Dhcp", "无法获取或续约 IP 地址，租约到期后可能回落到 169.254 链路本地地址",
            "Netman", "网络连接管理与网络状态刷新异常，网络配置变更可能无法生效",
            "NlaSvc", "网络位置识别异常，网络配置档（公用/专用）判定不准，"
                    + "进而影响防火墙配置档选择、网络发现与文件共享",
            "WlanSvc", "无法扫描或连接任何无线网络");

    public Module16DnsClientService() {
        super("16", "检查DNS客户端服务", FaultLayer.DNS_TRANSPORT, 3);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-Service 采集网络相关服务状态");
        CmdResult raw = context.ps(Probes.KEY_SERVICES_CORE, Probes.SERVICES_CORE);
        List<Map<String, Object>> services = context.psJson(Probes.KEY_SERVICES_CORE, Probes.SERVICES_CORE);

        context.step("检查服务运行状态与启动类型");
        boolean wirelessPresent = NetFacts.wirelessAdapterCount(context) > 0;
        List<String> stopped = new ArrayList<>();
        List<String> stoppedImpacts = new ArrayList<>();
        List<String> notAutomatic = new ArrayList<>();
        List<String> notApplicable = new ArrayList<>();
        List<String> details = new ArrayList<>();
        Set<String> queried = new LinkedHashSet<>();

        for (Map<String, Object> service : services) {
            String name = MapAccess.text(service, "Name");
            String displayName = MapAccess.text(service, "DisplayName");
            String status = Enums.serviceStatus(MapAccess.text(service, "Status"));
            String startType = Enums.serviceStartType(MapAccess.text(service, "StartType"));
            queried.add(name);
            boolean applicable = applicable(name, wirelessPresent);
            details.add(name + "（" + displayName + "）：状态=" + status + "，启动类型=" + startType
                    + (applicable ? "" : "，本机无无线网卡硬件，该服务不参与判定"));
            if (!applicable) {
                notApplicable.add(name + "（" + displayName + "）");
                continue;
            }
            if (!"Running".equalsIgnoreCase(status)) {
                stopped.add(name + "（" + displayName + "：" + status + "）");
                stoppedImpacts.add(name + " 停止 → " + SERVICE_IMPACT.getOrDefault(name, "对应的网络功能受影响"));
            }
            if (!"Automatic".equalsIgnoreCase(startType)) {
                notAutomatic.add(name + "（" + startType + "）");
            }
        }

        context.step("判定服务异常并生成结论");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (services.isEmpty()) {
            status = Status.FAIL;
            summary = "未能查询到任何网络相关服务状态";
            diagnosis = "服务查询失败，可能服务控制管理器异常或权限不足";
            suggestion = "以管理员身份重新运行本工具；并在“服务”管理单元中确认相关服务存在";
        } else if (!stopped.isEmpty()) {
            status = Status.FAIL;
            summary = "以下需要运行的服务未运行：" + String.join("、", stopped);
            diagnosis = "判定规则为“任一需要运行的服务未运行即失败”，本次命中的实际影响："
                    + String.join("；", stoppedImpacts) + "。";
            suggestion = "1.在“服务”管理单元中将上述服务启动，并设为“自动”；"
                    + "2.若无法启动，请查看系统事件日志中的服务错误信息；"
                    + "3.排查第三方优化软件是否禁用了这些服务（本工具只读，不会修改服务状态）";
        } else if (!notAutomatic.isEmpty()) {
            status = Status.WARN;
            summary = "关键服务均在运行，但以下服务的启动类型不是“自动”：" + String.join("、", notAutomatic);
            diagnosis = "启动类型为手动/禁用时，重启后服务可能不会自动拉起，导致重启后网络异常";
            suggestion = "在“服务”管理单元中把这些服务的启动类型改为“自动”（本工具只读，不会修改服务配置）";
        } else {
            status = Status.PASS;
            summary = "已查询的 " + queried.size() + " 个网络相关服务均处于运行状态且启动类型为自动（"
                    + String.join("、", queried) + "）"
                    + (notApplicable.isEmpty() ? ""
                    : "；" + String.join("、", notApplicable) + " 因本机无对应硬件不参与判定");
            diagnosis = "DNS 客户端及其依赖服务工作正常";
            suggestion = "无";
        }

        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("服务状态盘点", "Get-Service Dnscache, Dhcp, Netman, NlaSvc, WlanSvc（只读查询）",
                        details.isEmpty() ? "未查询到服务" : String.join("\n", details))
                .evidence("Get-Service 原始输出", raw)
                .build();
    }

    /** 服务是否适用于本机：WlanSvc 需要无线网卡硬件。 */
    private static boolean applicable(String serviceName, boolean wirelessPresent) {
        return !"WlanSvc".equalsIgnoreCase(serviceName) || wirelessPresent;
    }
}
