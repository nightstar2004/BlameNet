package com.blamenet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 外部验证清单条目。
 * <p>
 * 对于 PE 启动、干净启动、物理替换测试、重装系统等无法在当前系统内自动执行的操作，
 * 工具不跳过，而是生成结构化的人工验证任务，指导用户外部完成并支持回填后二次分析。
 */
@JsonPropertyOrder({"ModuleId", "TaskTitle", "Instructions", "UserFillResult", "Priority", "FilledAt"})
public class ManualValidationItem {

    /** 关联模块编号。 */
    @JsonProperty("ModuleId")
    public String moduleId;

    /** 任务标题。 */
    @JsonProperty("TaskTitle")
    public String taskTitle;

    /** 操作说明。 */
    @JsonProperty("Instructions")
    public String instructions;

    /** 用户填写结果；null 表示待填写。 */
    @JsonProperty("UserFillResult")
    public String userFillResult;

    /** 扩展：优先级建议。 */
    @JsonProperty("Priority")
    public String priority;

    /** 扩展：回填时间（UTC ISO-8601）；未回填时为 null。 */
    @JsonProperty("FilledAt")
    public String filledAt;

    public ManualValidationItem() {
    }

    public ManualValidationItem(String moduleId, String taskTitle, String instructions) {
        this.moduleId = moduleId;
        this.taskTitle = taskTitle;
        this.instructions = instructions;
    }

    public boolean isFilled() {
        return userFillResult != null && !userFillResult.isBlank();
    }
}
