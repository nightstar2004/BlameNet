package com.blamenet.report;

import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.Report;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.TextUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 报告包说明文件（README.txt）导出器。
 * <p>
 * 与其它导出器一致，输入只能是 JSON 主数据源反序列化出的 {@link Report}。
 * 版式遵循要求文档 6 节示例：报告编号 / 包内文件 / 推荐查看顺序 / 主数据源说明 /
 * 状态与风险等级对照 / 核心原则 / 手动验证项 / 隐私与脱敏提示 / 校验与完整性 / 生成信息。
 */
public final class ReadmeExporter implements Exporter {

    /** ZIP 内说明文件的固定名称（不带报告编号前缀）。 */
    public static final String README_FILE_NAME = "README.txt";

    private static final int WIDTH = 80;

    @Override
    public String id() {
        return "readme";
    }

    @Override
    public String displayName() {
        return "报告包说明（README.txt）";
    }

    @Override
    public String extension() {
        return "txt";
    }

    @Override
    public String manifestRole() {
        return "bundle-readme";
    }

    @Override
    public String mimeType() {
        return "text/plain";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        Path file = paths.directory().resolve(README_FILE_NAME);
        Files.writeString(file, render(report, paths), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    public static String render(Report report, ReportPaths paths) {
        StringBuilder sb = new StringBuilder();
        String json = paths.jsonFileName();

        banner(sb, "BlameNet 网络诊断报告包说明");
        sb.append('\n');
        kv(sb, "报告编号", paths.reportId());
        kv(sb, "生成时间", report.summary.reportGeneratedAt);
        kv(sb, "测试状态", summaryChinese(report.testMeta.status));
        kv(sb, "风险等级", riskChinese(report.summary.riskLevel));
        kv(sb, "主数据源", json);
        sb.append('\n').append('\n');

        section(sb, "包内文件");
        int column = paths.jsonFileName().length() + 4;
        fileRow(sb, json, column, "唯一主数据源，机器可读，适合审计和二次分析");
        fileRow(sb, paths.fileName("html"), column, "网页报告，推荐普通用户优先查看");
        fileRow(sb, paths.fileName("pdf"), column, "PDF 报表（简洁版），打印、归档、发送给他人");
        fileRow(sb, paths.fileName("full", "pdf"), column, "PDF 报表（完整版），含全部证据原文，适合深度排查");
        fileRow(sb, paths.fileName("txt"), column, "纯文本报告，适合快速查看");
        fileRow(sb, paths.fileName("xlsx"), column, "表格报告，适合筛选、统计、二次处理");
        fileRow(sb, README_FILE_NAME, column, "当前说明文件");
        fileRow(sb, "manifest.json", column, "包清单，记录文件角色、类型、SHA-256 等");
        fileRow(sb, paths.zipFileName(), column, "全部文件的打包结果");
        sb.append('\n').append('\n');

        section(sb, "推荐查看顺序");
        sb.append("1. 普通用户：先打开 ").append(paths.fileName("html")).append("。\n");
        sb.append("2. 需要打印或归档：打开 ").append(paths.fileName("pdf")).append("（简洁版）。\n");
        sb.append("3. 需要完整证据原文：打开 ").append(paths.fileName("full", "pdf")).append("（完整版）。\n");
        sb.append("4. 需要筛选和统计：打开 ").append(paths.fileName("xlsx")).append("。\n");
        sb.append("5. 需要技术文档：打开 ").append(paths.fileName("md")).append("。\n");
        sb.append("6. 需要纯文本快照：打开 ").append(paths.fileName("txt")).append("。\n");
        sb.append("7. 需要审计、二次开发或程序处理：使用 ").append(json).append("。\n");
        sb.append("8. 需要校验文件完整性：查看 manifest.json。\n");
        sb.append('\n').append('\n');

        section(sb, "主数据源说明");
        sb.append("主数据源为：\n\n").append(json).append("\n\n");
        sb.append("其顶层结构包括：\n\n");
        sb.append("TestMeta              测试元数据\n");
        sb.append("TestEnvironment       测试环境与预检证据\n");
        sb.append("Summary               总体摘要\n");
        sb.append("ModuleResults         模块结果列表\n");
        sb.append("ManualValidationList  手动验证清单\n\n");
        sb.append("注意：\n");
        sb.append("JSON 中的字段名、状态源值、原始命令、原始输出、记录时间保持原样，不翻译、不改写。\n");
        sb.append("例如：\n\n");
        kv(sb, "TestStartTime", report.testMeta.testStartTime);
        kv(sb, "TestEndTime", report.testMeta.testEndTime);
        kv(sb, "TestDuration", report.testMeta.testDuration);
        kv(sb, "Status", report.testMeta.status);
        kv(sb, "AdminPrivilege", String.valueOf(report.testMeta.adminPrivilege));
        sb.append("\n展示给用户时，状态和风险等级会映射为中文，但 JSON 主数据源仍保留源值。\n");
        sb.append("模块统计：总计 ").append(report.summary.totalModuleCount)
                .append(" 项，通过 ").append(report.summary.passCount)
                .append("，警告 ").append(report.summary.warnCount)
                .append("，失败 ").append(report.summary.failCount)
                .append("，手动 ").append(report.summary.manualCount)
                .append("，手动验证任务 ").append(report.summary.manualValidationCount)
                .append("，信息 ").append(report.summary.infoCount)
                .append("；证据条目 ").append(report.evidenceCount()).append(" 条。\n");
        if (TextUtil.isNotBlank(report.summary.globalDiagnosis)) {
            sb.append("\n全局诊断：\n").append(wrapIndent(report.summary.globalDiagnosis)).append('\n');
        }
        sb.append('\n').append('\n');

        section(sb, "状态与风险等级对照");
        sb.append("源值        中文展示\n");
        sb.append("Pass        通过\n");
        sb.append("Warn        警告\n");
        sb.append("Fail        失败\n");
        sb.append("Manual      手动\n");
        sb.append("Info        信息\n");
        sb.append("Completed   已完成\n");
        sb.append("Aborted     已中止\n");
        sb.append("Failed      失败\n");
        sb.append("Critical    严重\n");
        sb.append("High        高\n");
        sb.append("Medium      中\n");
        sb.append("Low         低\n");
        sb.append('\n').append('\n');

        section(sb, "核心原则");
        sb.append("* JSON 是唯一采集结果，HTML/PDF/MD/TXT/XLSX 不得各自采集。\n");
        sb.append("* 展示层中文化：通过、警告、失败、手动、已完成、严重、模块编号等。\n");
        sb.append("* 源数据层原样：TestStartTime、Status: Completed、AdminPrivilege: true、RawCommand、"
                + "RawOutput、RecordTime、JSON key、原始命令输出等不翻译、不改写。\n");
        sb.append("* 进度条：测试界面底部实时进度条，上方显示当前测试内容，每个小步骤完成都必须推进进度。\n");
        sb.append("* 结果目录：只写入安装目录下 results/，每次诊断在 results/ 下创建「BlameNet-yyyy-MM-dd-HH-mm-ss报告」子文件夹，\n");
        sb.append("  报告编号（BlameNet-yyyy-MM-dd-HH-mm-ss）与子文件夹名前缀一致，全部文件写入该子文件夹内。\n");
        sb.append("* 只读诊断：本工具不执行任何配置变更命令，修复动作一律只给建议。\n");
        sb.append('\n').append('\n');

        section(sb, "手动验证项");
        List<ManualValidationItem> items = report.manualValidationList;
        if (items.isEmpty()) {
            sb.append("本次诊断未产生需要外部验证的检查项。\n");
        } else {
            sb.append("如果报告中存在 Manual 状态，表示该检查无法在当前系统自动完成，需要用户按操作说明外部验证。\n");
            sb.append("完成后，可将结果回填到 BlameNet 工具中，进行二次分析或重新生成报告。\n\n");
            int index = 1;
            for (ManualValidationItem item : items) {
                sb.append("任务 ").append(index++).append("\n");
                kv(sb, "模块编号", item.moduleId);
                kv(sb, "任务标题", item.taskTitle);
                kv(sb, "操作说明", item.instructions);
                kv(sb, "优先级", item.priority);
                kv(sb, "用户填写结果", item.isFilled() ? item.userFillResult : "null / 待填写");
                if (TextUtil.isNotBlank(item.filledAt)) {
                    kv(sb, "回填时间", item.filledAt);
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        section(sb, "隐私与脱敏提示");
        sb.append("报告可能包含以下敏感信息：\n\n");
        sb.append("MAC 地址\nIP 地址\n默认网关\nDNS 服务器\n主机名\nWi-Fi SSID\n用户名\n文件路径\n"
                + "原始命令输出中的其他环境信息\n\n");
        sb.append("分享报告前，请根据实际需要脱敏。\n");
        sb.append("本工具仅进行只读诊断，不会自动修改网络配置。\n");
        sb.append("结果文件仅写入本机 results 目录，不上传任何服务器。\n");
        sb.append('\n').append('\n');

        section(sb, "校验与完整性");
        sb.append("manifest.json 记录包内文件的角色、类型和 SHA-256。\n");
        sb.append("可使用以下命令校验文件完整性。\n\n");
        sb.append("Windows PowerShell：\n\n");
        sb.append("Get-FileHash .\\").append(json).append(" -Algorithm SHA256\n");
        sb.append("Get-FileHash .\\").append(paths.fileName("html")).append(" -Algorithm SHA256\n\n");
        sb.append("Linux：\n\n");
        sb.append("sha256sum ").append(json).append('\n');
        sb.append("sha256sum ").append(paths.fileName("html")).append("\n\n");
        sb.append("将结果与 manifest.json 中的 sha256 对比。\n");
        sb.append('\n').append('\n');

        section(sb, "生成信息");
        kv(sb, "生成器", BuildInfo.toolName() + " " + BuildInfo.toolVersion());
        kv(sb, "Schema", "BlameNet.ReportBundle/1.0");
        kv(sb, "报告编号", paths.reportId());
        kv(sb, "主数据源", json);
        kv(sb, "生成时间", report.summary.reportGeneratedAt);
        kv(sb, "JDK", report.testMeta.javaVersion);
        kv(sb, "Maven", report.testMeta.buildTool);
        kv(sb, "运行档位", report.testMeta.runProfile);
        sb.append('\n').append('\n');

        banner(sb, "报告包说明结束");
        return sb.toString();
    }

    private static void banner(StringBuilder sb, String title) {
        sb.append("=".repeat(WIDTH)).append('\n');
        sb.append(title).append('\n');
        sb.append("=".repeat(WIDTH)).append('\n');
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('[').append(title).append("]\n");
        sb.append("-".repeat(WIDTH)).append('\n');
    }

    private static void kv(StringBuilder sb, String label, String value) {
        sb.append(padTo(13)).append(label).append(" : ").append(TextUtil.nvl(value)).append('\n');
    }

    private static void fileRow(StringBuilder sb, String name, int column, String description) {
        sb.append(name);
        sb.append(" ".repeat(Math.max(3, column - name.length())));
        sb.append(description).append('\n');
    }

    /** 按字符宽度左对齐填充（ASCII 与中文混排时按字符数近似对齐，保持可读）。 */
    private static String padTo(int width) {
        return " ".repeat(Math.max(0, width));
    }

    private static String wrapIndent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String paragraph : TextUtil.nvl(text).split("\n")) {
            StringBuilder line = new StringBuilder("    ");
            for (int i = 0; i < paragraph.length(); i++) {
                if (line.length() >= 76) {
                    sb.append(line).append('\n');
                    line.setLength(0);
                    line.append("    ");
                }
                line.append(paragraph.charAt(i));
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String summaryChinese(String sourceValue) {
        return switch (TextUtil.nvl(sourceValue).trim()) {
            case "Completed" -> "已完成（源值 Completed）";
            case "Aborted" -> "已中止（源值 Aborted）";
            case "Failed" -> "失败（源值 Failed）";
            default -> TextUtil.nvl(sourceValue);
        };
    }

    private static String riskChinese(String sourceValue) {
        return switch (TextUtil.nvl(sourceValue).trim()) {
            case "Critical" -> "严重（源值 Critical）";
            case "High" -> "高（源值 High）";
            case "Medium" -> "中（源值 Medium）";
            case "Low" -> "低（源值 Low）";
            default -> TextUtil.nvl(sourceValue);
        };
    }
}
