package com.blamenet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试环境快照（扩展顶层节点）。
 * <p>
 * 用于承载“本次诊断在什么环境下、以什么权限、通过什么外壳采集”的完整证据链，
 * 使 TestMeta 保持精简的元数据语义，同时不丢失任何只读预检结论。
 */
@JsonPropertyOrder({
        "HostName", "UserName", "Domain", "Manufacturer", "Model",
        "OSCaption", "OSVersion", "OSBuild", "OSArchitecture", "SystemDrive",
        "TotalMemoryMB", "TimeZoneOffsetMinutes",
        "AdminCheckEvidence", "ToolAvailability", "CollectorShell", "ProbeCommandCount", "DataSources"
})
public class TestEnvironment {

    @JsonProperty("HostName")
    public String hostName;

    @JsonProperty("UserName")
    public String userName;

    @JsonProperty("Domain")
    public String domain;

    @JsonProperty("Manufacturer")
    public String manufacturer;

    @JsonProperty("Model")
    public String model;

    @JsonProperty("OSCaption")
    public String osCaption;

    @JsonProperty("OSVersion")
    public String osVersion;

    @JsonProperty("OSBuild")
    public String osBuild;

    @JsonProperty("OSArchitecture")
    public String osArchitecture;

    @JsonProperty("SystemDrive")
    public String systemDrive;

    @JsonProperty("TotalMemoryMB")
    public Long totalMemoryMb;

    @JsonProperty("TimeZoneOffsetMinutes")
    public Integer timeZoneOffsetMinutes;

    /** 管理员权限判定的真实证据。 */
    @JsonProperty("AdminCheckEvidence")
    public Evidence adminCheckEvidence;

    /** 关键工具可用性：工具名 → 可执行文件真实路径（不可用则记录为空字符串）。 */
    @JsonProperty("ToolAvailability")
    public Map<String, String> toolAvailability = new LinkedHashMap<>();

    /** 采集外壳信息（PowerShell / CMD 的可执行文件路径与外壳参数）。 */
    @JsonProperty("CollectorShell")
    public Map<String, String> collectorShell = new LinkedHashMap<>();

    /** 预检阶段真实执行的命令条数。 */
    @JsonProperty("ProbeCommandCount")
    public Integer probeCommandCount;

    /** 本次诊断使用到的数据源说明（只读采集，不修改任何配置）。 */
    @JsonProperty("DataSources")
    public String dataSources;
}
