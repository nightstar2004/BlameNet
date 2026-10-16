package com.blamenet.model;

/**
 * 故障分层归属。
 * <p>
 * 依据设计文档“三、诊断调度与报告”的总判定规则：不同区段的模块失败，指向不同的故障层面。
 */
public enum FaultLayer {

    HARDWARE_DRIVER("硬件/驱动层", "模块 01-04 出现失败，指向网卡硬件、驱动或链路物理层问题", 1, 5),
    IP_ROUTING("IP/路由层", "模块 06-09 出现失败，指向 IP 配置、DHCP 与路由表问题", 6, 9),
    LINK("链路层", "模块 10-12 出现失败，指向本机协议栈与到网关的链路连通性", 10, 13),
    DNS_TRANSPORT("DNS/传输层", "模块 14-17 出现失败，指向 DNS 解析与远程端口可达性", 14, 17),
    SECURITY_PROXY("安全/代理问题", "模块 18-20 出现失败，指向防火墙、代理与 hosts 劫持", 18, 20),
    SERVICE_SYSTEM("驱动/系统服务问题", "模块 21-25 出现失败，指向驱动版本、电源节能、关键服务与系统文件完整性", 21, 25),
    DEEP_PACKET("深度数据包问题", "模块 26-28 出现失败，指向丢包归因、邻居发现与 MTU 分片问题", 26, 28),
    EXTERNAL("外部验证项", "模块 29-31 为外部验证项，需要用户在本机之外的环境完成对比测试", 29, 31);

    private final String chinese;
    private final String description;
    private final int fromModule;
    private final int toModule;

    FaultLayer(String chinese, String description, int fromModule, int toModule) {
        this.chinese = chinese;
        this.description = description;
        this.fromModule = fromModule;
        this.toModule = toModule;
    }

    public String chinese() {
        return chinese;
    }

    public String description() {
        return description;
    }

    public int fromModule() {
        return fromModule;
    }

    public int toModule() {
        return toModule;
    }

    /** 模块归属该层（用于报告分类字段）。 */
    public boolean covers(String moduleId) {
        long id = parseModuleId(moduleId);
        return id >= fromModule && id <= toModule;
    }

    /**
     * 失败是否归属于该层。
     * <p>
     * 严格沿用设计文档的总判定区段：硬件/驱动层仅 01-04、链路层仅 10-12，
     * 模块 05 与 13 属于补充性检查，不计入故障层归属。
     */
    public boolean attributesFailure(String moduleId) {
        long id = parseModuleId(moduleId);
        return switch (this) {
            case HARDWARE_DRIVER -> id >= 1 && id <= 4;
            case IP_ROUTING -> id >= 6 && id <= 9;
            case LINK -> id >= 10 && id <= 12;
            case DNS_TRANSPORT -> id >= 14 && id <= 17;
            case SECURITY_PROXY -> id >= 18 && id <= 20;
            case SERVICE_SYSTEM -> id >= 21 && id <= 25;
            case DEEP_PACKET -> id >= 26 && id <= 28;
            case EXTERNAL -> false;
        };
    }

    public static long parseModuleId(String moduleId) {
        if (moduleId == null) {
            return -1;
        }
        try {
            return Long.parseLong(moduleId.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
