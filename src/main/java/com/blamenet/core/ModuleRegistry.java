package com.blamenet.core;

import com.blamenet.modules.Module01AdapterRecognized;
import com.blamenet.modules.Module02PnpDeviceStatus;
import com.blamenet.modules.Module03WlanInterface;
import com.blamenet.modules.Module04AdapterDiscardCounters;
import com.blamenet.modules.Module05PhysicalSwapTest;
import com.blamenet.modules.Module06IpConfiguration;
import com.blamenet.modules.Module07DhcpLease;
import com.blamenet.modules.Module08IpConflict;
import com.blamenet.modules.Module09RoutingTable;
import com.blamenet.modules.Module10LoopbackStack;
import com.blamenet.modules.Module11LocalAddress;
import com.blamenet.modules.Module12GatewayConnectivity;
import com.blamenet.modules.Module13TcpStatistics;
import com.blamenet.modules.Module14DnsResolution;
import com.blamenet.modules.Module15DnsCache;
import com.blamenet.modules.Module16DnsClientService;
import com.blamenet.modules.Module17RemoteTcpPort;
import com.blamenet.modules.Module18FirewallRules;
import com.blamenet.modules.Module19ProxySettings;
import com.blamenet.modules.Module20HostsFile;
import com.blamenet.modules.Module21DriverVersion;
import com.blamenet.modules.Module22AdapterPowerSaving;
import com.blamenet.modules.Module23PowerPlan;
import com.blamenet.modules.Module24KeyServices;
import com.blamenet.modules.Module25SystemFileCheck;
import com.blamenet.modules.Module26PktmonCapture;
import com.blamenet.modules.Module27NeighborDiscovery;
import com.blamenet.modules.Module28MtuFragmentation;
import com.blamenet.modules.Module29CleanBoot;
import com.blamenet.modules.Module30PeLiveUsb;
import com.blamenet.modules.Module31AfterReinstall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块注册表：31 个检查模块的固定执行顺序（01 → 31）。
 * <p>
 * 顺序即资深网络工程师的排查顺序：先确认硬件与驱动，再看 IP/路由，然后验证链路连通性，
 * 接着排查 DNS 与端口，之后是安全/代理、系统服务与驱动，最后是深度数据包分析与外部验证项。
 */
public final class ModuleRegistry {

    private ModuleRegistry() {
    }

    /** 全部 31 个模块（顺序固定）。 */
    public static List<CheckModule> all() {
        List<CheckModule> modules = new ArrayList<>(31);
        modules.add(new Module01AdapterRecognized());
        modules.add(new Module02PnpDeviceStatus());
        modules.add(new Module03WlanInterface());
        modules.add(new Module04AdapterDiscardCounters());
        modules.add(new Module05PhysicalSwapTest());
        modules.add(new Module06IpConfiguration());
        modules.add(new Module07DhcpLease());
        modules.add(new Module08IpConflict());
        modules.add(new Module09RoutingTable());
        modules.add(new Module10LoopbackStack());
        modules.add(new Module11LocalAddress());
        modules.add(new Module12GatewayConnectivity());
        modules.add(new Module13TcpStatistics());
        modules.add(new Module14DnsResolution());
        modules.add(new Module15DnsCache());
        modules.add(new Module16DnsClientService());
        modules.add(new Module17RemoteTcpPort());
        modules.add(new Module18FirewallRules());
        modules.add(new Module19ProxySettings());
        modules.add(new Module20HostsFile());
        modules.add(new Module21DriverVersion());
        modules.add(new Module22AdapterPowerSaving());
        modules.add(new Module23PowerPlan());
        modules.add(new Module24KeyServices());
        modules.add(new Module25SystemFileCheck());
        modules.add(new Module26PktmonCapture());
        modules.add(new Module27NeighborDiscovery());
        modules.add(new Module28MtuFragmentation());
        modules.add(new Module29CleanBoot());
        modules.add(new Module30PeLiveUsb());
        modules.add(new Module31AfterReinstall());
        return modules;
    }

    /** 按编号过滤（空集合返回全部）。 */
    public static List<CheckModule> filtered(Set<String> moduleFilter) {
        List<CheckModule> modules = all();
        if (moduleFilter == null || moduleFilter.isEmpty()) {
            return modules;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : moduleFilter) {
            normalized.add(Options.normalizeModuleId(id));
        }
        List<CheckModule> result = new ArrayList<>();
        for (CheckModule module : modules) {
            if (normalized.contains(module.id())) {
                result.add(module);
            }
        }
        return result;
    }
}
