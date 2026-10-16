package com.blamenet.core;

import com.blamenet.collect.CmdResult;
import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 结果构造器。
 * <p>
 * 把「状态 + 摘要 + 根因推断 + 修复建议 + 证据链」组织为一个模块结果，
 * 证据统一走 {@link #evidence(String, CmdResult)}，确保 RawCommand / RawOutput / RecordTime 都是真实运行内容。
 */
public final class ResultBuilder {

    /** 单个证据保留的最大字符数；超出部分截断并明确标注，完整内容以 JSON 主数据源为准。 */
    private static final int EVIDENCE_LIMIT = 60_000;

    private final CheckModule module;
    private Status status = Status.INFO;
    private String summary = "";
    private String diagnosis = "";
    private String suggestion = "无";
    private ManualValidationItem manualTask;
    private final List<Evidence> evidences = new ArrayList<>();

    private ResultBuilder(CheckModule module) {
        this.module = module;
    }

    public static ResultBuilder of(CheckModule module) {
        return new ResultBuilder(module);
    }

    public ResultBuilder status(Status status) {
        this.status = status;
        return this;
    }

    public ResultBuilder summary(String summary) {
        this.summary = TextUtil.nvl(summary);
        return this;
    }

    public ResultBuilder diagnosis(String diagnosis) {
        this.diagnosis = TextUtil.nvl(diagnosis);
        return this;
    }

    public ResultBuilder suggestion(String suggestion) {
        this.suggestion = TextUtil.isBlank(suggestion) ? "无" : suggestion;
        return this;
    }

    /** 记录一次真实命令执行的证据。 */
    public ResultBuilder evidence(String evidenceName, CmdResult result) {
        if (result == null) {
            return this;
        }
        TextUtil.Truncated truncated = TextUtil.truncate(result.output(), EVIDENCE_LIMIT);
        Evidence evidence = new Evidence(evidenceName, result.command(), truncated.text(), result.recordTime());
        evidence.exitCode = result.executed() ? result.exitCode() : null;
        evidence.timedOut = result.timedOut();
        evidence.durationMillis = result.durationMillis();
        evidence.truncated = truncated.truncated();
        evidence.originalOutputLength = truncated.originalLength();
        evidences.add(evidence);
        return this;
    }

    /** 记录一条自定义证据（例如本地文件读取、外部验证任务说明）。 */
    public ResultBuilder evidenceRaw(String evidenceName, String rawCommand, String rawOutput) {
        return evidenceRaw(evidenceName, rawCommand, rawOutput, TimeUtil.isoNow());
    }

    /** 记录一条自定义证据并指定记录时间。 */
    public ResultBuilder evidenceRaw(String evidenceName, String rawCommand, String rawOutput, String recordTime) {
        return evidenceRaw(evidenceName, rawCommand, rawOutput, recordTime, EVIDENCE_LIMIT);
    }

    /** 记录一条自定义证据并指定记录时间与截断上限（保留 Truncated / OriginalOutputLength 标记）。 */
    public ResultBuilder evidenceRaw(String evidenceName, String rawCommand, String rawOutput, String recordTime,
                                     int limit) {
        TextUtil.Truncated truncated = TextUtil.truncate(rawOutput, limit);
        Evidence evidence = new Evidence(evidenceName, rawCommand, truncated.text(), recordTime);
        evidence.truncated = truncated.truncated();
        evidence.originalOutputLength = truncated.originalLength();
        evidences.add(evidence);
        return this;
    }

    public ResultBuilder evidence(Evidence evidence) {
        if (evidence != null) {
            evidences.add(evidence);
        }
        return this;
    }

    /** 声明本模块产生的外部验证任务（进入报告的 ManualValidationList）。 */
    public ResultBuilder manualTask(String title, String instructions, String priority) {
        ManualValidationItem item = new ManualValidationItem(module.id(), title, instructions);
        item.priority = priority;
        this.manualTask = item;
        return this;
    }

    public ModuleResult build() {
        ModuleResult result = new ModuleResult();
        result.moduleId = module.id();
        result.moduleName = module.name();
        result.status = status.sourceValue();
        result.summary = summary;
        result.diagnosis = diagnosis;
        result.suggestion = suggestion;
        result.evidences = evidences;
        result.category = module.layer().chinese();
        result.requiresAdmin = module.requiresAdmin();
        result.manualTask = manualTask;
        return result;
    }
}
