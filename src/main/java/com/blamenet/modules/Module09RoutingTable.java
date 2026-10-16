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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块 09：检查路由表。
 * <p>
 * 采集：Get-NetRoute -AddressFamily IPv4（结构化）+ Get-NetIPInterface（接口跃点）+ route print -4（原始证据）。
 * <p>
 * 判定：无 0.0.0.0/0 默认路由 → Fail；多条默认路由 → Warn；有效跃点为 0 → Warn。
 * <p>
 * 注意跃点口径：{@code Get-NetRoute.RouteMetric} 只是“路由自身跃点”，Windows 默认即为 0，
 * 而 {@code route print} 显示的“跃点数”是 <b>路由跃点 + 接口跃点</b> 之和（有效跃点）。
 * 因此这里按有效跃点判定，避免把正常机器的默认路由全部误报为“优先级被手动提高”。
 */
public final class Module09RoutingTable extends AbstractCheckModule {

    public Module09RoutingTable() {
        super("09", "检查路由表", FaultLayer.IP_ROUTING, 4);
    }

    @Override
    protected ModuleResult run(DiagnosticContext context) {
        context.step("执行 Get-NetRoute 采集 IPv4 路由表");
        List<Map<String, Object>> routes = context.psJson(Probes.KEY_ROUTE_V4, Probes.ROUTE_V4);
        CmdResult routeRaw = context.cmd(Probes.KEY_ROUTE_PRINT, Probes.CMDP_ROUTE_PRINT);

        context.step("读取各接口的接口跃点（InterfaceMetric）用于折算有效跃点");
        List<Map<String, Object>> interfaces = context.psJson(Probes.KEY_NET_IP_INTERFACE, Probes.NET_IP_INTERFACE);
        Map<String, Long> metricByAlias = new LinkedHashMap<>();
        Map<Long, Long> metricByIndex = new LinkedHashMap<>();
        for (Map<String, Object> ipInterface : interfaces) {
            long metric = MapAccess.number(ipInterface, "InterfaceMetric", -1);
            if (metric < 0) {
                continue;
            }
            metricByAlias.put(MapAccess.text(ipInterface, "InterfaceAlias"), metric);
            metricByIndex.put(MapAccess.number(ipInterface, "InterfaceIndex", -1), metric);
        }

        context.step("统计默认路由与异常路由条目");
        List<Map<String, Object>> defaultRoutes = new ArrayList<>();
        List<String> unusualRoutes = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            String prefix = MapAccess.text(route, "DestinationPrefix");
            String nextHop = MapAccess.text(route, "NextHop");
            String alias = MapAccess.text(route, "InterfaceAlias");
            long metric = MapAccess.number(route, "RouteMetric", -1);
            long interfaceMetric = interfaceMetricOf(route, alias, metricByAlias, metricByIndex);
            long effective = metric < 0 ? -1 : metric + Math.max(0, interfaceMetric);
            details.add(prefix + " → " + nextHop + "（接口 " + alias
                    + "，路由跃点=" + metric
                    + "，接口跃点=" + (interfaceMetric < 0 ? "未知" : String.valueOf(interfaceMetric))
                    + "，有效跃点=" + (effective < 0 ? "未知" : String.valueOf(effective))
                    + "，协议=" + MapAccess.text(route, "Protocol")
                    + "，存储=" + MapAccess.text(route, "Store") + "）");
            if ("0.0.0.0/0".equals(prefix)) {
                defaultRoutes.add(route);
            }
            if (effective == 0 && interfaceMetric >= 0) {
                unusualRoutes.add(prefix + " → " + nextHop + " 的有效跃点为 0（路由跃点 "
                        + metric + " + 接口跃点 " + interfaceMetric + "，优先级被手动置为最高）");
            }
        }

        context.step("判定默认路由与度量值是否合理");
        Status status;
        String summary;
        String diagnosis;
        String suggestion;
        if (routes.isEmpty()) {
            status = Status.FAIL;
            summary = "未能读取到任何 IPv4 路由条目";
            diagnosis = "路由表为空或采集失败，本机无法进行任何 IP 层转发";
            suggestion = "确认网卡已启用；在管理员命令行中执行 route print 核对（本工具只读，不会修改路由表）";
        } else if (defaultRoutes.isEmpty()) {
            status = Status.FAIL;
            summary = "路由表中不存在 0.0.0.0/0 默认路由，出网流量没有出口（共 " + routes.size() + " 条路由）";
            diagnosis = "缺少默认路由通常由“未获得网关”导致，根源多为 DHCP 未下发网关或静态配置遗漏网关；"
                    + "表现为可以访问局域网但完全无法上网";
            suggestion = "1.先修复模块 06/07 中的地址与网关问题；2.确认路由器已下发网关；"
                    + "3.如需手动添加路由，请在管理员命令行中执行 route add（本工具只读，不会自动执行）";
        } else if (defaultRoutes.size() > 1) {
            List<String> hops = new ArrayList<>();
            for (Map<String, Object> route : defaultRoutes) {
                String alias = MapAccess.text(route, "InterfaceAlias");
                long metric = MapAccess.number(route, "RouteMetric", -1);
                long interfaceMetric = interfaceMetricOf(route, alias, metricByAlias, metricByIndex);
                hops.add(MapAccess.text(route, "NextHop")
                        + "（有效跃点 " + (metric < 0 ? "未知" : String.valueOf(metric + Math.max(0, interfaceMetric)))
                        + "，接口 " + alias + "）");
            }
            status = Status.WARN;
            summary = "存在 " + defaultRoutes.size() + " 条默认路由：" + String.join("；", hops);
            diagnosis = "多默认网关常见于同时连接有线与无线、或安装了虚拟机网卡/代理软件；"
                    + "若度量值接近，会导致出口选择抖动，表现为网络时通时断";
            suggestion = "保留一条可用默认路由即可：可断开不用的网卡，或调整网卡接口跃点（本工具只读，不会修改路由）";
        } else if (!unusualRoutes.isEmpty()) {
            status = Status.WARN;
            summary = "默认路由存在，但发现异常跃点配置：" + String.join("；", unusualRoutes);
            diagnosis = "有效跃点被置 0 会强制该路由优先于其他链路，可能是优化软件或人为配置遗留";
            suggestion = "确认该配置是否符合预期；如非必要，请把接口跃点恢复为自动跃点（本工具只读）";
        } else {
            Map<String, Object> route = defaultRoutes.get(0);
            String alias = MapAccess.text(route, "InterfaceAlias");
            long metric = MapAccess.number(route, "RouteMetric", -1);
            long interfaceMetric = interfaceMetricOf(route, alias, metricByAlias, metricByIndex);
            status = Status.PASS;
            summary = "默认路由正常：0.0.0.0/0 → " + MapAccess.text(route, "NextHop")
                    + "（接口 " + alias + "，路由跃点=" + metric
                    + "，接口跃点=" + (interfaceMetric < 0 ? "未知" : String.valueOf(interfaceMetric))
                    + "，有效跃点=" + (metric < 0 ? "未知" : String.valueOf(metric + Math.max(0, interfaceMetric)))
                    + "，共 " + routes.size() + " 条路由）";
            diagnosis = "路由表结构完整，存在唯一默认路由且有效跃点正常"
                    + "（Windows 下默认路由的路由跃点本身就是 0，有效跃点由接口跃点决定）";
            suggestion = "无";
        }

        context.step("生成结论与修复建议");
        return result()
                .status(status)
                .summary(summary)
                .diagnosis(diagnosis)
                .suggestion(suggestion)
                .evidenceRaw("IPv4 路由条目盘点", "BlameNet 汇总：Get-NetRoute -AddressFamily IPv4 + Get-NetIPInterface",
                        details.isEmpty() ? "未读取到路由条目" : String.join("\n", details))
                .evidence("route print -4 原始输出", routeRaw)
                .build();
    }

    /** 按接口别名 / 接口索引查找接口跃点；未知时返回 -1。 */
    private static long interfaceMetricOf(Map<String, Object> route, String alias,
                                          Map<String, Long> byAlias, Map<Long, Long> byIndex) {
        Long metric = byAlias.get(alias);
        if (metric != null) {
            return metric;
        }
        Long byIfIndex = byIndex.get(MapAccess.number(route, "ifIndex", -1));
        return byIfIndex == null ? -1 : byIfIndex;
    }
}
