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

/**
 * 纯文本报表导出器。
 * <p>
 * 版式与设计要求中的 TXT 示例一致：分节使用等号/短横线分隔，展示层中文化，
 * 但“测试元数据”一节保留源字段名与源值（不做翻译与改写）。
 */
public final class TxtExporter implements Exporter {

    private static final String LINE = "=".repeat(80);
    private static final String THIN = "-".repeat(80);

    @Override
    public String id() {
        return "txt";
    }

    @Override
    public String displayName() {
        return "纯文本报告（TXT）";
    }

    @Override
    public String extension() {
        return "txt";
    }

    @Override
    public String manifestRole() {
        return "plain-text-report";
    }

    @Override
    public String mimeType() {
        return "text/plain";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        String text = render(report, paths);
        Path file = paths.file(extension());
        Files.writeString(file, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /** 渲染纯文本报告。 */
    public static String render(Report report, ReportPaths paths) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        RiskLevel risk = RiskLevel.fromSource(report.summary.riskLevel);
        TestStatus testStatus = TestStatus.fromSource(report.testMeta.status);

        sb.append(LINE).append('\n');
        sb.append("BlameNet 网络诊断报告\n");
        sb.append("报告编号 : ").append(paths.reportId()).append('\n');
        sb.append("数据源   : ").append(paths.jsonFileName()).append('\n');
        sb.append("生成时间 : ").append(TextUtil.nvl(report.summary.reportGeneratedAt)).append('\n');
        sb.append(LINE).append("\n\n");

        sb.append("[测试元数据 - 源数据字段保持原样]\n").append(THIN).append('\n');
        sb.append(pad("TestStartTime")).append(": ").append(TextUtil.nvl(report.testMeta.testStartTime)).append('\n');
        sb.append(pad("TestEndTime")).append(": ").append(TextUtil.nvl(report.testMeta.testEndTime)).append('\n');
        sb.append(pad("TestDuration")).append(": ").append(TextUtil.nvl(report.testMeta.testDuration)).append('\n');
        sb.append(pad("Status")).append(": ").append(TextUtil.nvl(report.testMeta.status)).append('\n');
        sb.append(pad("AdminPrivilege")).append(": ").append(report.testMeta.adminPrivilege).append("\n\n");

        sb.append("[报告展示]\n").append(THIN).append('\n');
        sb.append(pad("测试状态")).append(": ").append(testStatus.chinese()).append('\n');
        sb.append(pad("管理员权限")).append(": ").append(report.testMeta.adminPrivilege ? "是" : "否").append('\n');
        sb.append(pad("风险等级")).append(": ").append(risk.chinese()).append("\n\n");

        sb.append("[摘要]\n").append(THIN).append('\n');
        sb.append(pad("模块总数")).append(": ").append(report.summary.totalModuleCount).append('\n');
        sb.append(pad("通过")).append(": ").append(report.summary.passCount).append('\n');
        sb.append(pad("警告")).append(": ").append(report.summary.warnCount).append('\n');
        sb.append(pad("失败")).append(": ").append(report.summary.failCount).append('\n');
        sb.append(pad("手动")).append(": ").append(report.summary.manualCount).append('\n');
        sb.append(pad("手动验证任务数")).append(": ").append(report.summary.manualValidationCount).append('\n');
        sb.append(pad("信息类")).append(": ").append(report.summary.infoCount).append("\n\n");
        sb.append("全局诊断:\n").append(TextUtil.nvl(report.summary.globalDiagnosis)).append("\n\n");
        if (report.summary.faultLayers != null && !report.summary.faultLayers.isEmpty()) {
            sb.append(pad("故障层定位")).append(": ").append(String.join("、", report.summary.faultLayers)).append("\n\n");
        }

        sb.append("[模块结果]\n");
        for (ModuleResult module : report.moduleResults) {
            Status status = Status.fromSource(module.status);
            sb.append(LINE).append('\n');
            sb.append('[').append(module.moduleId).append("] ").append(module.moduleName).append('\n');
            sb.append(pad("模块编号")).append(": ").append(module.moduleId).append('\n');
            sb.append(pad("状态")).append(": ").append(status.chinese()).append('\n');
            sb.append(pad("分类")).append(": ").append(TextUtil.nvl(module.category)).append('\n');
            sb.append(pad("摘要")).append(": ").append(TextUtil.nvl(module.summary)).append('\n');
            sb.append(pad("诊断")).append(": ").append(TextUtil.nvl(module.diagnosis)).append('\n');
            sb.append(pad("建议")).append(": ").append(TextUtil.nvl(module.suggestion)).append('\n');
            sb.append(pad("耗时")).append(": ").append(TextUtil.nvl(module.duration))
                    .append("（执行命令 ").append(module.executedCommandCount).append(" 条）\n");
            if (module.manualVerified != null && module.manualVerified) {
                sb.append(pad("用户回填")).append(": ").append(TextUtil.nvl(module.manualNote)).append('\n');
            }
            sb.append('\n');
            int index = 0;
            for (Evidence evidence : module.evidences) {
                index++;
                sb.append("证据 ").append(index).append(":\n");
                sb.append("  证据名称 : ").append(TextUtil.nvl(evidence.evidenceName)).append('\n');
                sb.append("  原始命令 : ").append(TextUtil.nvl(evidence.rawCommand)).append('\n');
                sb.append("  原始输出 :\n").append(indent(evidence.rawOutput, "    ")).append('\n');
                sb.append("  记录时间 : ").append(TextUtil.nvl(evidence.recordTime));
                if (evidence.exitCode != null) {
                    sb.append("（退出码 ").append(evidence.exitCode).append("）");
                }
                if (Boolean.TRUE.equals(evidence.truncated)) {
                    sb.append("（输出已截断，原始长度 ").append(evidence.originalOutputLength).append(" 字符）");
                }
                sb.append("\n\n");
            }
        }

        sb.append("[手动验证清单]（共 ")
                .append(report.manualValidationList == null ? 0 : report.manualValidationList.size())
                .append(" 项）\n").append(THIN).append('\n');
        if (report.manualValidationList == null || report.manualValidationList.isEmpty()) {
            sb.append("本次诊断没有产生外部验证任务。\n");
        } else {
            for (ManualValidationItem item : report.manualValidationList) {
                sb.append(LINE).append('\n');
                sb.append('[').append(item.moduleId).append("] ").append(TextUtil.nvl(item.taskTitle)).append('\n');
                sb.append(pad("模块编号")).append(": ").append(item.moduleId).append('\n');
                sb.append(pad("任务标题")).append(": ").append(TextUtil.nvl(item.taskTitle)).append('\n');
                sb.append(pad("操作说明")).append(": ").append(TextUtil.nvl(item.instructions)).append('\n');
                sb.append(pad("优先级")).append(": ").append(TextUtil.nvl(item.priority)).append('\n');
                sb.append(pad("用户填写结果")).append(": ")
                        .append(item.isFilled() ? item.userFillResult : "null / 待填写").append("\n\n");
            }
        }

        sb.append("[环境信息]\n").append(THIN).append('\n');
        sb.append(pad("主机名")).append(": ").append(TextUtil.nvl(report.testEnvironment.hostName)).append('\n');
        sb.append(pad("当前用户")).append(": ").append(TextUtil.nvl(report.testEnvironment.userName)).append('\n');
        sb.append(pad("操作系统")).append(": ").append(TextUtil.nvl(report.testEnvironment.osCaption))
                .append(" ").append(TextUtil.nvl(report.testEnvironment.osVersion))
                .append(" Build ").append(TextUtil.nvl(report.testEnvironment.osBuild)).append('\n');
        sb.append(pad("系统架构")).append(": ").append(TextUtil.nvl(report.testEnvironment.osArchitecture)).append('\n');
        sb.append(pad("运行 JDK")).append(": ").append(TextUtil.nvl(report.testMeta.javaVersion)).append('\n');
        sb.append(pad("构建工具")).append(": ").append(TextUtil.nvl(report.testMeta.buildTool)).append("\n\n");

        sb.append("[隐私与脱敏提示]\n").append(THIN).append('\n');
        sb.append("报告可能包含 MAC 地址、IP 地址、默认网关、DNS 服务器、主机名、Wi-Fi SSID、").append('\n');
        sb.append("用户名与文件路径等敏感信息。分享报告前，请根据实际需要脱敏。").append('\n');
        sb.append("本工具仅进行只读诊断，不会自动修改网络配置。").append("\n\n");

        sb.append("[校验与完整性]\n").append(THIN).append('\n');
        sb.append("manifest.json 记录包内文件的角色、类型和 SHA-256。可使用以下命令校验文件完整性。").append('\n');
        sb.append("Windows PowerShell：").append('\n');
        sb.append("Get-FileHash .\\").append(paths.jsonFileName()).append(" -Algorithm SHA256").append('\n');
        sb.append("Linux：").append('\n');
        sb.append("sha256sum ").append(paths.jsonFileName()).append("\n\n");

        sb.append(LINE).append('\n');
        sb.append("报告结束").append('\n');
        sb.append(LINE).append('\n');
        return sb.toString();
    }

    private static String pad(String label) {
        StringBuilder sb = new StringBuilder(label == null ? "" : label);
        while (sb.length() < 13) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String indent(String text, String prefix) {
        if (text == null || text.isEmpty()) {
            return prefix + "(无输出)";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : TextUtil.normalize(text).split("\n", -1)) {
            sb.append(prefix).append(line).append('\n');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
