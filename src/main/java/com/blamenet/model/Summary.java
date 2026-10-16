package com.blamenet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 总体摘要。
 * <p>
 * 前 7 个字段与设计要求示例一致（含 GlobalDiagnosis 与 RiskLevel 的源值），
 * 其余为扩展统计与定位信息。
 */
@JsonPropertyOrder({
        "TotalModuleCount", "PassCount", "WarnCount", "FailCount", "ManualCount", "InfoCount",
        "ManualValidationCount",
        "GlobalDiagnosis", "RiskLevel",
        "FaultLayers", "FailingModules", "WarningModules", "ManualModules", "InfoModules",
        "ReportGeneratedAt", "DataSourceJson"
})
public class Summary {

    @JsonProperty("TotalModuleCount")
    public int totalModuleCount;

    @JsonProperty("PassCount")
    public int passCount;

    @JsonProperty("WarnCount")
    public int warnCount;

    @JsonProperty("FailCount")
    public int failCount;

    @JsonProperty("ManualCount")
    public int manualCount;

    /** 扩展：Info 状态（不适用 / 无法判定）的模块数量。 */
    @JsonProperty("InfoCount")
    public int infoCount;

    /**
     * 扩展：手动验证清单条目数（与 ManualCount 口径不同）。
     * <p>
     * ManualCount 表示状态为 Manual 的模块数；本字段表示 ManualValidationList 的实际条目数，
     * 二者可能不一致（一个模块可产生多条外部验证任务，或清单条目与模块非一一对应）。
     */
    @JsonProperty("ManualValidationCount")
    public int manualValidationCount;

    @JsonProperty("GlobalDiagnosis")
    public String globalDiagnosis;

    /** 源值：Critical / High / Medium / Low。 */
    @JsonProperty("RiskLevel")
    public String riskLevel;

    /** 扩展：本次诊断定位到的故障层（中文）。 */
    @JsonProperty("FaultLayers")
    public List<String> faultLayers = new ArrayList<>();

    /** 扩展：失败模块（形如 "06 查看完整IP配置"）。 */
    @JsonProperty("FailingModules")
    public List<String> failingModules = new ArrayList<>();

    /** 扩展：警告模块。 */
    @JsonProperty("WarningModules")
    public List<String> warningModules = new ArrayList<>();

    /** 扩展：手动验证模块。 */
    @JsonProperty("ManualModules")
    public List<String> manualModules = new ArrayList<>();

    /** 扩展：信息类模块。 */
    @JsonProperty("InfoModules")
    public List<String> infoModules = new ArrayList<>();

    /** 扩展：报告生成时间（UTC ISO-8601）。 */
    @JsonProperty("ReportGeneratedAt")
    public String reportGeneratedAt;

    /** 扩展：主数据源文件名。 */
    @JsonProperty("DataSourceJson")
    public String dataSourceJson;
}
