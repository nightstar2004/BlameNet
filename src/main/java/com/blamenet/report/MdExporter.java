package com.blamenet.report;

import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.model.TestStatus;
import com.blamenet.util.TextUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Markdown 报表导出器。
 * <p>
 * 结构遵循设计要求中的 markdown 示例：元数据表、摘要表、模块详情（含可折叠证据块）、手动验证清单与附录。
 * 测试元数据表保留源字段名与源值。
 */
public final class MdExporter implements Exporter {

    @Override
    public String id() {
        return "md";
    }

    @Override
    public String displayName() {
        return "Markdown 报告（MD）";
    }

    @Override
    public String extension() {
        return "md";
    }

    @Override
    public String manifestRole() {
        return "markdown-report";
    }

    @Override
    public String mimeType() {
        return "text/markdown";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        String text = render(report, paths);
        Path file = paths.file(extension());
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /** 渲染 Markdown 报告。 */
    public static String render(Report report, ReportPaths paths) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        RiskLevel risk = RiskLevel.fromSource(report.summary.riskLevel);
        TestStatus testStatus = TestStatus.fromSource(report.testMeta.status);

        sb.append("# BlameNet 网络诊断报告\n\n");
        sb.append("> 报告编号：`").append(paths.reportId()).append("`  \n");
        sb.append("> 数据源：`").append(paths.jsonFileName()).append("`  \n");
        sb.append("> 生成时间：`").append(TextUtil.nvl(report.summary.reportGeneratedAt)).append("`  \n");
        sb.append("> 测试状态：").append(testStatus.chinese()).append(" · 风险等级：").append(risk.chinese()).append("\n\n");

        sb.append("## 1. 测试元数据\n\n");
        sb.append("| 字段 | 值 |\n| --- | --- |\n");
        row(sb, "TestStartTime", report.testMeta.testStartTime);
        row(sb, "TestEndTime", report.testMeta.testEndTime);
        row(sb, "TestDuration", report.testMeta.testDuration);
        row(sb, "Status", report.testMeta.status);
        row(sb, "AdminPrivilege", String.valueOf(report.testMeta.adminPrivilege));
        sb.append("\n> 以上字段为源数据字段，保持原样，不做翻译与改写。\n\n");

        sb.append("## 2. 摘要\n\n");
        sb.append("| 指标 | 值 |\n| --- | ---: |\n");
        row(sb, "模块总数", String.valueOf(report.summary.totalModuleCount));
        row(sb, "通过", String.valueOf(report.summary.passCount));
        row(sb, "警告", String.valueOf(report.summary.warnCount));
        row(sb, "失败", String.valueOf(report.summary.failCount));
        row(sb, "手动", String.valueOf(report.summary.manualCount));
        row(sb, "手动验证任务数", String.valueOf(report.summary.manualValidationCount));
        row(sb, "信息类", String.valueOf(report.summary.infoCount));
        row(sb, "测试状态", testStatus.chinese());
        row(sb, "风险等级", risk.chinese());
        row(sb, "证据条数", String.valueOf(report.evidenceCount()));
        sb.append("\n**全局诊断：**  \n").append(TextUtil.nvl(report.summary.globalDiagnosis)).append("\n\n");
        if (report.summary.faultLayers != null && !report.summary.faultLayers.isEmpty()) {
            sb.append("**故障层定位：** ").append(String.join("、", report.summary.faultLayers)).append("\n\n");
        }

        sb.append("## 3. 模块结果\n\n");
        for (ModuleResult module : report.moduleResults) {
            Status status = Status.fromSource(module.status);
            sb.append("### ").append(module.moduleId).append(' ').append(module.moduleName).append("\n\n");
            sb.append("- **模块编号：** ").append(module.moduleId).append('\n');
            sb.append("- **状态：** ").append(status.chinese()).append("（源值 ").append(module.status).append("）\n");
            sb.append("- **分类：** ").append(TextUtil.nvl(module.category)).append('\n');
            sb.append("- **摘要：** ").append(TextUtil.nvl(module.summary)).append('\n');
            sb.append("- **诊断：** ").append(TextUtil.nvl(module.diagnosis)).append('\n');
            sb.append("- **建议：** ").append(TextUtil.nvl(module.suggestion)).append('\n');
            sb.append("- **耗时：** ").append(TextUtil.nvl(module.duration))
                    .append("（执行命令 ").append(module.executedCommandCount).append(" 条）\n");
            if (module.manualVerified != null && module.manualVerified) {
                sb.append("- **用户回填：** ").append(TextUtil.nvl(module.manualNote)).append('\n');
            }
            sb.append('\n');
            for (Evidence evidence : module.evidences) {
                sb.append("<details>\n<summary>证据：").append(TextUtil.escapeHtml(evidence.evidenceName))
                        .append("</summary>\n\n");
                sb.append("<p><strong>原始命令</strong></p>\n\n");
                sb.append("<pre><code>").append(TextUtil.escapeHtml(TextUtil.nvl(evidence.rawCommand)))
                        .append("</code></pre>\n\n");
                sb.append("<p><strong>原始输出</strong></p>\n\n");
                sb.append("<pre><code>").append(TextUtil.escapeHtml(TextUtil.nvl(evidence.rawOutput)))
                        .append("</code></pre>\n\n");
                sb.append("<p><strong>记录时间：</strong> <code>").append(TextUtil.nvl(evidence.recordTime)).append("</code>");
                if (evidence.exitCode != null) {
                    sb.append("，退出码 <code>").append(evidence.exitCode).append("</code>");
                }
                if (Boolean.TRUE.equals(evidence.timedOut)) {
                    sb.append("，已超时强制结束");
                }
                if (Boolean.TRUE.equals(evidence.truncated)) {
                    sb.append("，输出已截断（原始长度 ").append(evidence.originalOutputLength).append(" 字符）");
                }
                sb.append("</p>\n\n</details>\n\n");
            }
            sb.append("---\n\n");
        }

        sb.append("## 4. 环境信息\n\n");
        sb.append("| 字段 | 值 |\n| --- | --- |\n");
        row(sb, "主机名", report.testEnvironment.hostName);
        row(sb, "当前用户", report.testEnvironment.userName);
        row(sb, "操作系统", TextUtil.nvl(report.testEnvironment.osCaption) + " "
                + TextUtil.nvl(report.testEnvironment.osVersion) + " Build " + TextUtil.nvl(report.testEnvironment.osBuild));
        row(sb, "系统架构", report.testEnvironment.osArchitecture);
        row(sb, "运行 JDK", report.testMeta.javaVersion);
        row(sb, "构建工具", report.testMeta.buildTool);
        row(sb, "运行档位", report.testMeta.runProfile);
        row(sb, "安装目录", report.testMeta.installDirectory);
        sb.append('\n');
        if (report.testEnvironment.toolAvailability != null && !report.testEnvironment.toolAvailability.isEmpty()) {
            sb.append("### 4.1 关键工具可用性\n\n| 工具 | 路径 |\n| --- | --- |\n");
            for (Map.Entry<String, String> entry : report.testEnvironment.toolAvailability.entrySet()) {
                row(sb, entry.getKey(), TextUtil.isBlank(entry.getValue()) ? "不可用" : entry.getValue());
            }
            sb.append('\n');
        }
        if (report.testEnvironment.collectorShell != null && !report.testEnvironment.collectorShell.isEmpty()) {
            sb.append("### 4.2 采集外壳\n\n| 项 | 值 |\n| --- | --- |\n");
            for (Map.Entry<String, String> entry : report.testEnvironment.collectorShell.entrySet()) {
                row(sb, entry.getKey(), entry.getValue());
            }
            sb.append('\n');
        }

        sb.append("## 5. 手动验证清单（共 ")
                .append(report.manualValidationList == null ? 0 : report.manualValidationList.size())
                .append(" 项）\n\n");
        sb.append("| 模块编号 | 任务标题 | 操作说明 | 用户填写结果 |\n| --- | --- | --- | --- |\n");
        if (report.manualValidationList == null || report.manualValidationList.isEmpty()) {
            sb.append("| - | 本次诊断没有产生外部验证任务 | - | - |\n");
        } else {
            for (ManualValidationItem item : report.manualValidationList) {
                sb.append("| ").append(item.moduleId).append(" | ").append(cell(item.taskTitle)).append(" | ")
                        .append(cell(item.instructions)).append(" | ")
                        .append(item.isFilled() ? cell(item.userFillResult) : "`null` / 待填写")
                        .append(" |\n");
            }
        }
        sb.append('\n');

        sb.append("---\n\n");
        sb.append("> 本工具只做只读诊断，不执行任何写操作。报告可能包含 MAC 地址、IP 地址、主机名、SSID 等敏感信息，");
        sb.append("分享前请自行脱敏。\n");
        sb.append("> 报告包内文件清单与 SHA-256 校验值见 README.txt 与 manifest.json。\n");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(cell(key)).append(" | ").append(cell(value)).append(" |\n");
    }

    /** 表格单元格转义：竖线与换行。 */
    private static String cell(String value) {
        return TextUtil.nvl(value).replace("|", "\\|").replace("\n", "<br>");
    }
}
