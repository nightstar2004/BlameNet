package com.blamenet.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告根对象 = 唯一主数据源。
 * <p>
 * HTML / PDF / MD / TXT / XLSX / README.txt / manifest.json 全部由本对象的 JSON 序列化结果
 * 反序列化后生成，导出层不重新采集任何数据，保证所有导出内容同源一致。
 */
@JsonPropertyOrder({"TestMeta", "TestEnvironment", "Summary", "ModuleResults", "ManualValidationList"})
public class Report {

    @JsonProperty("TestMeta")
    public TestMeta testMeta = new TestMeta();

    /** 扩展顶层节点：测试环境与预检证据链。 */
    @JsonProperty("TestEnvironment")
    public TestEnvironment testEnvironment = new TestEnvironment();

    @JsonProperty("Summary")
    public Summary summary = new Summary();

    @JsonProperty("ModuleResults")
    public List<ModuleResult> moduleResults = new ArrayList<>();

    @JsonProperty("ManualValidationList")
    public List<ManualValidationItem> manualValidationList = new ArrayList<>();

    public Report() {
    }

    @JsonIgnore
    public String reportId() {
        return testMeta == null ? null : testMeta.reportId;
    }

    @JsonIgnore
    public ModuleResult findModule(String moduleId) {
        if (moduleId == null) {
            return null;
        }
        for (ModuleResult result : moduleResults) {
            if (moduleId.equals(result.moduleId)) {
                return result;
            }
        }
        return null;
    }

    @JsonIgnore
    public List<ManualValidationItem> pendingManualItems() {
        List<ManualValidationItem> pending = new ArrayList<>();
        for (ManualValidationItem item : manualValidationList) {
            if (!item.isFilled()) {
                pending.add(item);
            }
        }
        return pending;
    }

    @JsonIgnore
    public int evidenceCount() {
        int count = 0;
        for (ModuleResult result : moduleResults) {
            count += result.evidences == null ? 0 : result.evidences.size();
        }
        return count;
    }
}
