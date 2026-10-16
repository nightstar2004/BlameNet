package com.blamenet.collect;

/**
 * 采集脚本与命令常量表。
 * <p>
 * 所有模块共享同一份脚本常量，好处是：
 * <ul>
 *     <li>同一份数据只采集一次（上下文按 key 缓存），报告中的数值必定自洽；</li>
 *     <li>数据结构化：PowerShell 侧统一用 ConvertTo-Json 输出，Java 侧统一按 JSON 解析，
 *         比解析人可读表格更可靠；</li>
 *     <li>原生工具（ipconfig / ping / netsh / powercfg / pktmon 等）保留原始文本输出作为证据。</li>
 * </ul>
 * 注意：脚本内一律避免使用双引号，以规避 Windows 命令行到 PowerShell 的转义问题。
 */
public final class Probes {

    // ---------- 网络适配器 ----------
    public static final String KEY_NET_ADAPTER = "netAdapter";
    public static final String NET_ADAPTER =
            "@(Get-NetAdapter | Select-Object Name,InterfaceDescription,InterfaceIndex,LinkSpeed,MacAddress,Virtual,"
                    + "DriverProvider,DriverVersion,"
                    + "@{n='Status';e={[string]$_.Status}},@{n='AdminStatus';e={[string]$_.AdminStatus}},@{n='MediaConnectionState';e={[string]$_.MediaConnectionState}},@{n='MediaType';e={[string]$_.MediaType}},@{n='PhysicalMediaType';e={[string]$_.PhysicalMediaType}},"
                    + "@{n='DriverDateText';e={if($_.DriverDate){$_.DriverDate.ToString('yyyy-MM-dd')}else{''}}}) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_PNP_NET = "pnpNetDevices";
    public static final String PNP_NET =
            "@(Get-PnpDevice -Class Net -ErrorAction SilentlyContinue | Select-Object Status,Class,FriendlyName,"
                    + "InstanceId,Problem,ProblemDescription) | ConvertTo-Json -Depth 4";

    public static final String KEY_WLAN_INTERFACES = "wlanInterfaces";
    public static final String CMDP_WLAN_INTERFACES = "netsh wlan show interfaces";

    public static final String KEY_ADAPTER_ADVANCED = "adapterAdvancedProperties";    public static final String ADAPTER_ADVANCED =
            "@(Get-NetAdapterAdvancedProperty -ErrorAction SilentlyContinue | Where-Object "
                    + "{$_.DisplayName -match 'Speed|Duplex|速度|双工|Flow|流'} | Select-Object Name,DisplayName,"
                    + "DisplayValue,RegistryKeyword) | ConvertTo-Json -Depth 4";

    public static final String KEY_ADAPTER_STATS_1 = "adapterStatisticsFirst";
    public static final String KEY_ADAPTER_STATS_2 = "adapterStatisticsSecond";
    public static final String ADAPTER_STATISTICS =
            "@(Get-NetAdapterStatistics | Select-Object Name,ReceivedBytes,ReceivedUnicastPackets,ReceivedDiscardedPackets,"
                    + "ReceivedPacketErrors,ReceivedMulticastPackets,SentBytes,OutboundUnicastPackets,OutboundDiscardedPackets,"
                    + "OutboundPacketErrors) | ConvertTo-Json -Depth 4";

    public static final String KEY_ADAPTER_POWER = "adapterPowerManagement";
    public static final String ADAPTER_POWER =
            "@(Get-NetAdapterPowerManagement -ErrorAction SilentlyContinue | Select-Object Name,"
                    + "@{n='AllowComputerToTurnOffDevice';e={[string]$_.AllowComputerToTurnOffDevice}},@{n='DeviceSleepOnDisconnect';e={[string]$_.DeviceSleepOnDisconnect}},@{n='WakeOnMagicPacket';e={[string]$_.WakeOnMagicPacket}},@{n='WakeOnPattern';e={[string]$_.WakeOnPattern}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_DEVICE_POWER_WMI = "devicePowerEnableWmi";
    public static final String DEVICE_POWER_WMI =
            "@(Get-CimInstance -Namespace root/wmi -ClassName MSPower_DeviceEnable -ErrorAction SilentlyContinue "
                    + "| Select-Object InstanceName,Enable) | ConvertTo-Json -Depth 4";

    // ---------- IP 配置 ----------
    public static final String KEY_IPCONFIG_ALL = "ipconfigAll";
    public static final String CMDP_IPCONFIG_ALL = "ipconfig /all";

    public static final String KEY_NET_IP_CONFIGURATION = "netIpConfiguration";
    public static final String NET_IP_CONFIGURATION =
            "@(Get-NetIPConfiguration | ForEach-Object { [pscustomobject]@{ InterfaceAlias=$_.InterfaceAlias; "
                    + "InterfaceIndex=$_.InterfaceIndex; InterfaceDescription=$_.InterfaceDescription; "
                    + "NetProfile=$_.NetProfile.Name; IPv4Address=($_.IPv4Address.IPAddress -join ','); "
                    + "PrefixLength=($_.IPv4Address.PrefixLength -join ','); DefaultGateway=($_.IPv4DefaultGateway.NextHop -join ','); "
                    + "IPv6Address=($_.IPv6Address.IPAddress -join ','); DnsServers=(($_.DNSServer | Where-Object "
                    + "{$_.AddressFamily -eq 2}).ServerAddresses -join ',') } }) | ConvertTo-Json -Depth 4";

    public static final String KEY_IP_ADDRESS_V4 = "ipAddressV4";
    public static final String IP_ADDRESS_V4 =
            "@(Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,InterfaceIndex,IPAddress,"
                    + "PrefixLength,ValidLifetime,PreferredLifetime,SkipAsSource,"
                    + "@{n='PrefixOrigin';e={[string]$_.PrefixOrigin}},@{n='SuffixOrigin';e={[string]$_.SuffixOrigin}},@{n='AddressState';e={[string]$_.AddressState}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_NET_IP_INTERFACE = "netIpInterfaceV4";
    public static final String NET_IP_INTERFACE =
            "@(Get-NetIPInterface -AddressFamily IPv4 | Select-Object InterfaceAlias,InterfaceIndex,"
                    + "AddressFamily,InterfaceMetric,Zone,NlMtu,"
                    + "@{n='Dhcp';e={[string]$_.Dhcp}},@{n='ConnectionState';e={[string]$_.ConnectionState}},@{n='Forwarding';e={[string]$_.Forwarding}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_DNS_CLIENT_SERVER = "dnsClientServerV4";
    public static final String DNS_CLIENT_SERVER =
            "@(Get-DnsClientServerAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,InterfaceIndex,ServerAddresses) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_DHCP_CONFIG = "win32AdapterConfiguration";
    public static final String DHCP_CONFIG =
            "@(Get-CimInstance Win32_NetworkAdapterConfiguration -Filter 'IPEnabled = TRUE' -ErrorAction SilentlyContinue "
                    + "| Select-Object Description,Index,DHCPEnabled,DHCPServer,IPAddress,IPSubnet,DefaultIPGateway,MACAddress,"
                    + "@{n='DHCPLeaseObtained';e={if($_.DHCPLeaseObtained){$_.DHCPLeaseObtained.ToString('yyyy-MM-dd HH:mm:ss')}else{''}}},"
                    + "@{n='DHCPLeaseExpires';e={if($_.DHCPLeaseExpires){$_.DHCPLeaseExpires.ToString('yyyy-MM-dd HH:mm:ss')}else{''}}}) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_DHCP_SERVICE = "dhcpServiceState";
    public static final String DHCP_SERVICE =
            "@(Get-Service Dhcp -ErrorAction SilentlyContinue | Select-Object Name,DisplayName,"
                    + "@{n='Status';e={[string]$_.Status}},@{n='StartType';e={[string]$_.StartType}},@{n='ServiceType';e={[string]$_.ServiceType}}) | ConvertTo-Json -Depth 4";

    // ---------- 邻居 / 路由 ----------
    public static final String KEY_ARP_TABLE = "arpTable";
    public static final String CMDP_ARP_TABLE = "arp -a";

    public static final String KEY_NEIGHBOR_V4 = "neighborV4";
    public static final String NEIGHBOR_V4 =
            "@(Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object ifIndex,InterfaceAlias,"
                    + "IPAddress,LinkLayerAddress,IsPermanent,"
                    + "@{n='State';e={[string]$_.State}},@{n='Type';e={[string]$_.Type}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_NEIGHBOR_V6 = "neighborV6";
    public static final String NEIGHBOR_V6 =
            "@(Get-NetNeighbor -AddressFamily IPv6 -ErrorAction SilentlyContinue | Select-Object ifIndex,InterfaceAlias,"
                    + "IPAddress,LinkLayerAddress,State,Type,IsPermanent) | ConvertTo-Json -Depth 4";

    public static final String KEY_ROUTE_V4 = "routeV4";
    public static final String ROUTE_V4 =
            "@(Get-NetRoute -AddressFamily IPv4 | Select-Object ifIndex,InterfaceAlias,DestinationPrefix,NextHop,"
                    + "RouteMetric,"
                    + "@{n='Protocol';e={[string]$_.Protocol}},@{n='Store';e={[string]$_.Store}},@{n='State';e={[string]$_.State}},@{n='Publish';e={[string]$_.Publish}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_ROUTE_PRINT = "routePrintV4";
    public static final String CMDP_ROUTE_PRINT = "route print -4";

    // ---------- DNS ----------
    public static final String KEY_DNS_RESOLVE = "dnsResolveBaidu";
    public static final String DNS_RESOLVE =
            "@(Resolve-DnsName www.baidu.com -ErrorAction SilentlyContinue | Select-Object Name,Type,IPAddress,NameHost,"
                    + "TTL,Section) | ConvertTo-Json -Depth 4";

    public static final String KEY_NSLOOKUP = "nslookupBaidu";
    public static final String CMDP_NSLOOKUP = "nslookup www.baidu.com";

    public static final String KEY_DNS_CACHE = "dnsClientCache";
    public static final String DNS_CACHE =
            "@(Get-DnsClientCache -ErrorAction SilentlyContinue | Select-Object Entry,Name,Data,Type,Status,"
                    + "TimeToLive,Section) | ConvertTo-Json -Depth 4";

    public static final String KEY_DISPLAY_DNS = "ipconfigDisplayDns";
    public static final String CMDP_DISPLAY_DNS = "ipconfig /displaydns";

    // ---------- TCP / 端口 / 代理 ----------
    public static final String KEY_TCP_CONNECTIONS = "tcpConnections";
    public static final String TCP_CONNECTIONS =
            "@(Get-NetTCPConnection -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,RemoteAddress,"
                    + "RemotePort,OwningProcess,"
                    + "@{n='State';e={[string]$_.State}},@{n='AppliedSetting';e={[string]$_.AppliedSetting}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_NETSTAT_ANO = "netstatAno";
    public static final String CMDP_NETSTAT_ANO = "netstat -ano";

    public static final String KEY_TEST_NET_CONNECTION = "testNetConnectionBaidu443";
    public static final String TEST_NET_CONNECTION =
            "Test-NetConnection -ComputerName www.baidu.com -Port 443 -InformationLevel Detailed | Format-List * | Out-String -Width 200";

    public static final String KEY_WINHTTP_PROXY = "winhttpProxy";
    public static final String CMDP_WINHTTP_PROXY = "netsh winhttp show proxy";

    public static final String KEY_IE_PROXY = "ieProxySettings";
    public static final String IE_PROXY =
            "@(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' "
                    + "-ErrorAction SilentlyContinue | Select-Object ProxyEnable,ProxyServer,ProxyOverride,AutoConfigURL,"
                    + "AutoDetect) | ConvertTo-Json -Depth 4";

    // ---------- 防火墙 / hosts ----------
    public static final String KEY_FIREWALL_PROFILES = "firewallProfiles";
    public static final String FIREWALL_PROFILES =
            "@(Get-NetFirewallProfile | Select-Object Name,Enabled,AllowInboundRules,AllowLocalFirewallRules,"
                    + "@{n='DefaultInboundAction';e={[string]$_.DefaultInboundAction}},@{n='DefaultOutboundAction';e={[string]$_.DefaultOutboundAction}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_FIREWALL_BLOCK = "firewallOutboundBlockRules";
    public static final String FIREWALL_BLOCK =
            "$rules=@(Get-NetFirewallRule -Enabled True -Direction Outbound -Action Block -ErrorAction SilentlyContinue); "
                    + "@{ Count=$rules.Count; Rules=@($rules | Select-Object -First 50 DisplayName,Profile,Direction,Action,Enabled) } "
                    + "| ConvertTo-Json -Depth 5";

    public static final String HOSTS_FILE_DISPLAY = "本地只读读取 C:\\Windows\\System32\\drivers\\etc\\hosts";

    // ---------- 服务 / 驱动 / 启动项 ----------
    public static final String KEY_SERVICES_CORE = "servicesCore";
    public static final String SERVICES_CORE =
            "@(Get-Service Dnscache,Dhcp,Netman,NlaSvc,WlanSvc -ErrorAction SilentlyContinue | Select-Object "
                    + "Name,DisplayName,@{n='Status';e={[string]$_.Status}},@{n='StartType';e={[string]$_.StartType}},@{n='ServiceType';e={[string]$_.ServiceType}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_SERVICES_EXTENDED = "servicesExtended";
    public static final String SERVICES_EXTENDED =
            "@(Get-Service Dhcp,Dnscache,Netman,NlaSvc,WlanSvc,Nsi,Netprofm -ErrorAction SilentlyContinue | Select-Object "
                    + "Name,DisplayName,@{n='Status';e={[string]$_.Status}},@{n='StartType';e={[string]$_.StartType}},@{n='ServiceType';e={[string]$_.ServiceType}}) | ConvertTo-Json -Depth 4";

    public static final String KEY_NET_DRIVERS = "netPnpDrivers";
    public static final String NET_DRIVERS =
            "@(Get-CimInstance Win32_PnPSignedDriver -ErrorAction SilentlyContinue | Where-Object {$_.DeviceClass -eq 'NET'} "
                    + "| Select-Object DeviceName,DriverVersion,DriverProviderName,InfName,IsSigned,"
                    + "@{n='DriverDateText';e={if($_.DriverDate){([datetime]$_.DriverDate).ToString('yyyy-MM-dd')}else{''}}}) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_STARTUP_COMMANDS = "startupCommands";
    public static final String STARTUP_COMMANDS =
            "@(Get-CimInstance Win32_StartupCommand -ErrorAction SilentlyContinue | Select-Object Name,Command,Location,User) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_THIRD_PARTY_SERVICES = "thirdPartyRunningServices";
    public static final String THIRD_PARTY_SERVICES =
            "@(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object "
                    + "{$_.State -eq 'Running' -and $_.PathName -notlike '*Windows\\System32*' -and "
                    + "$_.PathName -notlike '*Windows\\SysWOW64*'} | Select-Object Name,DisplayName,PathName,StartMode) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_THIRD_PARTY_DRIVERS = "thirdPartyRunningDrivers";
    public static final String THIRD_PARTY_DRIVERS =
            "@(Get-CimInstance Win32_SystemDriver -ErrorAction SilentlyContinue | Where-Object "
                    + "{$_.State -eq 'Running' -and $_.PathName -notlike '*Windows\\System32*'} | Select-Object Name,"
                    + "DisplayName,PathName,State,StartMode) | ConvertTo-Json -Depth 4";

    // ---------- 电源计划 ----------
    public static final String KEY_POWERCFG_SCHEME = "powercfgActiveScheme";
    public static final String CMDP_POWERCFG_SCHEME = "powercfg /getactivescheme";

    public static final String KEY_POWERCFG_PCIE = "powercfgPcieSubgroup";
    public static final String CMDP_POWERCFG_PCIE =
            "powercfg /query SCHEME_CURRENT 501a4d13-42af-4429-9fd1-a8218c268e20";

    public static final String KEY_POWERCFG_WIRELESS = "powercfgWirelessSubgroup";
    public static final String CMDP_POWERCFG_WIRELESS =
            "powercfg /query SCHEME_CURRENT 19cbb8fa-5279-450e-9fac-8a3d5fedd0c1";

    public static final String KEY_POWERCFG_ALL = "powercfgQueryAll";
    public static final String CMDP_POWERCFG_ALL = "powercfg /query";

    // ---------- 系统文件完整性 ----------
    public static final String KEY_SFC = "sfcVerifyOnly";
    public static final String CMDP_SFC = "sfc /verifyonly";

    public static final String KEY_DISM = "dismScanHealth";
    public static final String CMDP_DISM = "DISM /Online /Cleanup-Image /ScanHealth";

    public static final String KEY_CBS_LOG = "cbsLogTail";
    public static final String CBS_LOG =
            "Get-Content 'C:\\Windows\\Logs\\CBS\\CBS.log' -Tail 40 -ErrorAction SilentlyContinue | Out-String -Width 300";

    // ---------- Pktmon ----------
    public static final String KEY_PKTMON_COMPONENTS = "pktmonComponents";
    public static final String CMDP_PKTMON_COMPONENTS = "pktmon comp list";

    // ---------- 磁盘 / 可移动设备 ----------
    public static final String KEY_REMOVABLE_DRIVES = "removableDrives";
    public static final String REMOVABLE_DRIVES =
            "@(Get-CimInstance Win32_DiskDrive -ErrorAction SilentlyContinue | Where-Object "
                    + "{$_.MediaType -eq 'Removable Media'} | Select-Object Model,InterfaceType,Size,SerialNumber,Status) "
                    + "| ConvertTo-Json -Depth 4";

    public static final String KEY_LOGICAL_DISKS = "logicalDisks";
    public static final String LOGICAL_DISKS =
            "@(Get-CimInstance Win32_LogicalDisk -ErrorAction SilentlyContinue | Where-Object {$_.DriveType -eq 3} "
                    + "| Select-Object DeviceID,VolumeName,FileSystem,Size,FreeSpace) | ConvertTo-Json -Depth 4";

    // ---------- 环境（引擎预检使用） ----------
    public static final String KEY_WIN32_OS = "win32OperatingSystem";
    public static final String KEY_WIN32_CS = "win32ComputerSystem";
    public static final String KEY_TOOL_AVAILABILITY = "toolAvailability";

    // ---------- 连通性（模块自身执行，按参数缓存） ----------
    public static final String CMDP_PING_LOOPBACK = "ping 127.0.0.1 -n 4";

    private Probes() {
    }
}
