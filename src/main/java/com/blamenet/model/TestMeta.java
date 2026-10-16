package com.blamenet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 测试元数据。
 * <p>
 * 前 5 个字段与设计要求示例完全一致，且在报告展示层保持源值原样
 * （TestStartTime、Status: Completed、AdminPrivilege: true 不翻译、不改写）；
 * 其余为扩展字段，用于记录真实运行环境，便于审计与二次分析。
 */
@JsonPropertyOrder({
        "TestStartTime", "TestEndTime", "TestDuration", "Status", "AdminPrivilege",
        "ReportId", "Tool", "ToolVersion", "Schema",
        "TimeZone", "Locale", "JavaVersion", "BuildTool", "RunProfile", "ModuleFilter",
        "InstallDirectory", "ResultsDirectory"
})
public class TestMeta {

    @JsonProperty("TestStartTime")
    public String testStartTime;

    @JsonProperty("TestEndTime")
    public String testEndTime;

    @JsonProperty("TestDuration")
    public String testDuration;

    /** 源值：Completed / Aborted / Failed。 */
    @JsonProperty("Status")
    public String status;

    @JsonProperty("AdminPrivilege")
    public boolean adminPrivilege;

    @JsonProperty("ReportId")
    public String reportId;

    @JsonProperty("Tool")
    public String tool;

    @JsonProperty("ToolVersion")
    public String toolVersion;

    @JsonProperty("Schema")
    public String schema;

    @JsonProperty("TimeZone")
    public String timeZone;

    @JsonProperty("Locale")
    public String locale;

    @JsonProperty("JavaVersion")
    public String javaVersion;

    @JsonProperty("BuildTool")
    public String buildTool;

    @JsonProperty("RunProfile")
    public String runProfile;

    @JsonProperty("ModuleFilter")
    public String moduleFilter;

    @JsonProperty("InstallDirectory")
    public String installDirectory;

    @JsonProperty("ResultsDirectory")
    public String resultsDirectory;
}
