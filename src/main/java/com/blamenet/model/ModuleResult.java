package com.blamenet.model;

import com.blamenet.util.TimeUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个检查模块的结果。
 * <p>
 * 对应设计文档中的 CheckResult：状态、摘要、证据链、根因推断、修复建议（不自动执行）。
 * 前 7 个字段与设计要求示例完全一致，其余为允许的扩展字段。
 */
@JsonPropertyOrder({
        "ModuleId", "ModuleName", "Status", "Summary", "Diagnosis", "Suggestion", "Evidences",
        "Category", "RequiresAdmin", "DurationMillis", "Duration", "ManualVerified", "ManualNote",
        "ExecutedCommandCount", "ManualTask"
})
public class ModuleResult {

    /** 模块编号，例如 "01"。 */
    @JsonProperty("ModuleId")
    public String moduleId;

    /** 模块名称，例如 "检查网络适配器是否被系统识别"。 */
    @JsonProperty("ModuleName")
    public String moduleName;

    /** 状态源值：Pass / Warn / Fail / Manual / Info。 */
    @JsonProperty("Status")
    public String status;

    /** 结论摘要。 */
    @JsonProperty("Summary")
    public String summary;

    /** 根因推断。 */
    @JsonProperty("Diagnosis")
    public String diagnosis;

    /** 修复建议（标注需手动执行；本工具不会自动修改任何配置）。 */
    @JsonProperty("Suggestion")
    public String suggestion;

    /** 证据链。 */
    @JsonProperty("Evidences")
    public List<Evidence> evidences = new ArrayList<>();

    /** 扩展：所属故障层分类。 */
    @JsonProperty("Category")
    public String category;

    /** 扩展：该检查是否依赖管理员权限。 */
    @JsonProperty("RequiresAdmin")
    public boolean requiresAdmin;

    /** 扩展：模块执行耗时（毫秒）。 */
    @JsonProperty("DurationMillis")
    public long durationMillis;

    /** 扩展：模块执行耗时（HH:mm:ss.SSS）。 */
    @JsonProperty("Duration")
    public String duration;

    /** 扩展：用户是否已对该手动项回填验证结果。 */
    @JsonProperty("ManualVerified")
    public Boolean manualVerified;

    /** 扩展：用户回填的说明。 */
    @JsonProperty("ManualNote")
    public String manualNote;

    /** 扩展：该模块真实执行的命令条数。 */
    @JsonProperty("ExecutedCommandCount")
    public int executedCommandCount;

    /**
     * 扩展：本模块产生的外部验证任务。
     * <p>
     * 无法在当前系统内自动执行的检查（物理替换测试、干净启动、PE 对比、重装建议）
     * 会在这里声明任务，由调度引擎汇总为 ManualValidationList，绝不跳过任何一步。
     */
    @JsonProperty("ManualTask")
    public ManualValidationItem manualTask;

    public ModuleResult() {
    }

    public Status statusEnum() {
        return Status.fromSource(status);
    }

    /** 用统一格式填充耗时字段。 */
    public void setDurationMillis(long millis) {
        this.durationMillis = millis;
        this.duration = TimeUtil.duration(millis);
    }
}
