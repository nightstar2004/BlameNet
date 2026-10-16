package com.blamenet.report;

import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.model.TestStatus;
import com.blamenet.util.TextUtil;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XLSX 报表导出器。
 * <p>
 * 工作表结构与设计要求一致：封面、测试元数据、摘要、模块结果、证据、手动验证清单。
 * 表名与展示列头中文化；“测试元数据”表保留源字段名与源值；模块结果表额外提供“状态源值”列。
 */
public final class XlsxExporter implements Exporter {

    /** Excel 单元格文本上限为 32767 字符，这里留出安全余量（避免边界处 API 抛异常）。 */
    private static final int CELL_LIMIT = 32_000;

    @Override
    public String id() {
        return "xlsx";
    }

    @Override
    public String displayName() {
        return "表格报告（XLSX）";
    }

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public String manifestRole() {
        return "spreadsheet-report";
    }

    @Override
    public String mimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        Path file = paths.file(extension());
        try (Workbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            coverSheet(workbook, styles, report, paths);
            metaSheet(workbook, styles, report);
            summarySheet(workbook, styles, report);
            moduleSheet(workbook, styles, report);
            evidenceSheet(workbook, styles, report);
            manualSheet(workbook, styles, report);
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                workbook.write(out);
            }
        }
        return file;
    }

    // ==================================================================================
    // Sheet 1：封面
    // ==================================================================================

    private static void coverSheet(Workbook workbook, Styles styles, Report report, ReportPaths paths) {
        Sheet sheet = workbook.createSheet("封面");
        int rowIndex = 0;
        setTitle(sheet, rowIndex++, "BlameNet 网络诊断报告", styles.titleStyle);
        rowIndex++;
        keyValue(sheet, rowIndex++, "报告编号", paths.reportId(), styles);
        keyValue(sheet, rowIndex++, "数据源", paths.jsonFileName(), styles);
        keyValue(sheet, rowIndex++, "测试状态", TestStatus.fromSource(report.testMeta.status).chinese(), styles);
        keyValue(sheet, rowIndex++, "风险等级", RiskLevel.fromSource(report.summary.riskLevel).chinese(), styles);
        keyValue(sheet, rowIndex++, "开始时间", report.testMeta.testStartTime, styles);
        keyValue(sheet, rowIndex++, "结束时间", report.testMeta.testEndTime, styles);
        keyValue(sheet, rowIndex++, "耗时", report.testMeta.testDuration, styles);
        keyValue(sheet, rowIndex++, "管理员权限", report.testMeta.adminPrivilege ? "是" : "否", styles);
        keyValue(sheet, rowIndex++, "主机名", report.testEnvironment.hostName, styles);
        keyValue(sheet, rowIndex++, "操作系统", TextUtil.nvl(report.testEnvironment.osCaption) + " "
                + TextUtil.nvl(report.testEnvironment.osVersion), styles);
        keyValue(sheet, rowIndex++, "报告生成时间", report.summary.reportGeneratedAt, styles);
        rowIndex++;
        cell(sheet, rowIndex, 0, "全局诊断", styles.headerStyle);
        cell(sheet, rowIndex, 1, report.summary.globalDiagnosis, styles.wrapStyle);
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 100 * 256);
    }

    // ==================================================================================
    // Sheet 2：测试元数据（源字段名与源值）
    // ==================================================================================

    private static void metaSheet(Workbook workbook, Styles styles, Report report) {
        Sheet sheet = workbook.createSheet("测试元数据");
        header(sheet, 0, "字段", "值");
        int rowIndex = 1;
        rowIndex = kv(sheet, rowIndex, "TestStartTime", report.testMeta.testStartTime, styles);
        rowIndex = kv(sheet, rowIndex, "TestEndTime", report.testMeta.testEndTime, styles);
        rowIndex = kv(sheet, rowIndex, "TestDuration", report.testMeta.testDuration, styles);
        rowIndex = kv(sheet, rowIndex, "Status", report.testMeta.status, styles);
        rowIndex = kv(sheet, rowIndex, "AdminPrivilege", String.valueOf(report.testMeta.adminPrivilege), styles);
        sheet.createRow(rowIndex + 1).createCell(0)
                .setCellValue("说明：以上 5 个字段为源数据字段，保持原样，不做翻译与改写。");
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("ReportId", report.testMeta.reportId);
        extras.put("Tool", report.testMeta.tool);
        extras.put("ToolVersion", report.testMeta.toolVersion);
        extras.put("Schema", report.testMeta.schema);
        extras.put("TimeZone", report.testMeta.timeZone);
        extras.put("Locale", report.testMeta.locale);
        extras.put("JavaVersion", report.testMeta.javaVersion);
        extras.put("BuildTool", report.testMeta.buildTool);
        extras.put("RunProfile", report.testMeta.runProfile);
        extras.put("ModuleFilter", report.testMeta.moduleFilter);
        extras.put("InstallDirectory", report.testMeta.installDirectory);
        extras.put("ResultsDirectory", report.testMeta.resultsDirectory);
        rowIndex += 3;
        rowIndex = kv(sheet, rowIndex, "扩展字段", "", styles);
        for (Map.Entry<String, String> entry : extras.entrySet()) {
            rowIndex = kv(sheet, rowIndex, entry.getKey(), entry.getValue(), styles);
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 90 * 256);
        sheet.createFreezePane(0, 1);
    }

    // ==================================================================================
    // Sheet 3：摘要
    // ==================================================================================

    private static void summarySheet(Workbook workbook, Styles styles, Report report) {
        Sheet sheet = workbook.createSheet("摘要");
        header(sheet, 0, "指标", "值");
        int rowIndex = 1;
        rowIndex = kv(sheet, rowIndex, "模块总数", String.valueOf(report.summary.totalModuleCount), styles);
        rowIndex = kv(sheet, rowIndex, "通过", String.valueOf(report.summary.passCount), styles);
        rowIndex = kv(sheet, rowIndex, "警告", String.valueOf(report.summary.warnCount), styles);
        rowIndex = kv(sheet, rowIndex, "失败", String.valueOf(report.summary.failCount), styles);
        rowIndex = kv(sheet, rowIndex, "手动", String.valueOf(report.summary.manualCount), styles);
        rowIndex = kv(sheet, rowIndex, "手动验证任务数", String.valueOf(report.summary.manualValidationCount), styles);
        rowIndex = kv(sheet, rowIndex, "信息类", String.valueOf(report.summary.infoCount), styles);
        rowIndex = kv(sheet, rowIndex, "测试状态", TestStatus.fromSource(report.testMeta.status).chinese(), styles);
        rowIndex = kv(sheet, rowIndex, "风险等级", RiskLevel.fromSource(report.summary.riskLevel).chinese(), styles);
        rowIndex = kv(sheet, rowIndex, "证据条数", String.valueOf(report.evidenceCount()), styles);
        rowIndex = kv(sheet, rowIndex, "故障层定位",
                report.summary.faultLayers == null ? "" : String.join("、", report.summary.faultLayers), styles);
        rowIndex = kv(sheet, rowIndex, "失败模块",
                report.summary.failingModules == null ? "" : String.join("；", report.summary.failingModules), styles);
        rowIndex = kv(sheet, rowIndex, "警告模块",
                report.summary.warningModules == null ? "" : String.join("；", report.summary.warningModules), styles);
        rowIndex = kv(sheet, rowIndex, "报告生成时间", report.summary.reportGeneratedAt, styles);
        cell(sheet, rowIndex, 0, "全局诊断", styles.headerStyle);
        cell(sheet, rowIndex, 1, report.summary.globalDiagnosis, styles.wrapStyle);
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 120 * 256);
    }

    // ==================================================================================
    // Sheet 4：模块结果
    // ==================================================================================

    private static void moduleSheet(Workbook workbook, Styles styles, Report report) {
        Sheet sheet = workbook.createSheet("模块结果");
        String[] headers = {"模块编号", "模块名称", "状态", "状态源值", "分类", "摘要", "诊断", "建议",
                "耗时", "执行命令数", "需要管理员权限"};
        Row head = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.headerStyle);
        }
        int rowIndex = 1;
        for (ModuleResult module : report.moduleResults) {
            Status status = Status.fromSource(module.status);
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, module.moduleId, styles.centerStyle);
            text(row, 1, module.moduleName, styles.wrapStyle);
            text(row, 2, status.chinese(), styles.statusStyle(status));
            text(row, 3, module.status, styles.centerStyle);
            text(row, 4, module.category, styles.wrapStyle);
            text(row, 5, module.summary, styles.wrapStyle);
            text(row, 6, module.diagnosis, styles.wrapStyle);
            text(row, 7, module.suggestion, styles.wrapStyle);
            text(row, 8, module.duration, styles.centerStyle);
            text(row, 9, String.valueOf(module.executedCommandCount), styles.centerStyle);
            text(row, 10, module.requiresAdmin ? "是" : "否", styles.centerStyle);
        }
        widths(sheet, 8, 30, 10, 10, 14, 52, 52, 52, 14, 12, 16);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, rowIndex - 1),
                0, headers.length - 1));
    }

    // ==================================================================================
    // Sheet 5：证据
    // ==================================================================================

    private static void evidenceSheet(Workbook workbook, Styles styles, Report report) {
        Sheet sheet = workbook.createSheet("证据");
        String[] headers = {"模块编号", "模块名称", "证据名称", "原始命令", "原始输出",
                "记录时间", "退出码", "执行耗时(ms)", "是否截断"};
        Row head = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.headerStyle);
        }
        int rowIndex = 1;
        for (ModuleResult module : report.moduleResults) {
            if (module.evidences == null) {
                continue;
            }
            for (Evidence evidence : module.evidences) {
                Row row = sheet.createRow(rowIndex++);
                text(row, 0, module.moduleId, styles.centerStyle);
                text(row, 1, module.moduleName, styles.wrapStyle);
                text(row, 2, evidence.evidenceName, styles.wrapStyle);
                text(row, 3, evidence.rawCommand, styles.wrapStyle);
                text(row, 4, evidence.rawOutput, styles.wrapStyle);
                text(row, 5, evidence.recordTime, styles.centerStyle);
                text(row, 6, evidence.exitCode == null ? "" : String.valueOf(evidence.exitCode), styles.centerStyle);
                text(row, 7, evidence.durationMillis == null ? "" : String.valueOf(evidence.durationMillis),
                        styles.centerStyle);
                text(row, 8, Boolean.TRUE.equals(evidence.truncated) ? "是" : "否", styles.centerStyle);
            }
        }
        widths(sheet, 10, 18, 22, 40, 80, 20, 10, 14, 10);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, rowIndex - 1),
                0, headers.length - 1));
    }

    // ==================================================================================
    // Sheet 6：手动验证清单
    // ==================================================================================

    private static void manualSheet(Workbook workbook, Styles styles, Report report) {
        Sheet sheet = workbook.createSheet("手动验证清单");
        Row head = sheet.createRow(0);
        String[] headers = {"模块编号", "任务标题", "操作说明", "优先级", "用户填写结果", "回填时间"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.headerStyle);
        }
        int rowIndex = 1;
        if (report.manualValidationList != null) {
            for (ManualValidationItem item : report.manualValidationList) {
                Row row = sheet.createRow(rowIndex++);
                text(row, 0, item.moduleId, styles.centerStyle);
                text(row, 1, item.taskTitle, styles.wrapStyle);
                text(row, 2, item.instructions, styles.wrapStyle);
                text(row, 3, item.priority, styles.wrapStyle);
                text(row, 4, item.isFilled() ? item.userFillResult : "null / 待填写", styles.wrapStyle);
                text(row, 5, item.filledAt, styles.centerStyle);
            }
        }
        widths(sheet, 10, 40, 90, 30, 40, 22);
        sheet.createFreezePane(0, 1);
    }

    // ==================================================================================
    // 通用工具
    // ==================================================================================

    private static void setTitle(Sheet sheet, int rowIndex, String title, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(style);
        sheet.setColumnWidth(0, 22 * 256);
    }

    private static void keyValue(Sheet sheet, int rowIndex, String key, String value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, key, styles.headerStyle);
        text(row, 1, value, styles.wrapStyle);
    }

    private static int kv(Sheet sheet, int rowIndex, String key, String value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, key, styles.headerStyle);
        text(row, 1, value, styles.wrapStyle);
        return rowIndex + 1;
    }

    private static void header(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
        }
    }

    private static void cell(Sheet sheet, int rowIndex, int columnIndex, String value, CellStyle style) {
        Row row = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) : sheet.getRow(rowIndex);
        text(row, columnIndex, value, style);
    }

    private static void text(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        String text = TextUtil.nvl(value);
        if (text.length() > CELL_LIMIT) {
            text = text.substring(0, CELL_LIMIT)
                    + "\n[BlameNet] 内容超长，已在表格中截断；完整内容请查看 JSON 主数据源。";
        }
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    /** 按列序号依次赋宽（columnWidths 依次对应第 0、1、2… 列）。 */
    private static void widths(Sheet sheet, int... columnWidths) {
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }
    }

    /** 样式集合。 */
    private static final class Styles {

        private final CellStyle titleStyle;
        private final CellStyle headerStyle;
        private final CellStyle wrapStyle;
        private final CellStyle centerStyle;
        private final Map<Status, CellStyle> statusStyles = new LinkedHashMap<>();

        Styles(Workbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);
            borders(wrapStyle);

            centerStyle = workbook.createCellStyle();
            centerStyle.setAlignment(HorizontalAlignment.CENTER);
            centerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            borders(centerStyle);

            for (Status status : Status.values()) {
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                switch (status) {
                    case PASS -> {
                        font.setColor(IndexedColors.DARK_GREEN.getIndex());
                        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
                    }
                    case FAIL -> {
                        font.setColor(IndexedColors.DARK_RED.getIndex());
                        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
                    }
                    case WARN -> {
                        font.setColor(IndexedColors.DARK_YELLOW.getIndex());
                        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
                    }
                    case MANUAL -> {
                        font.setColor(IndexedColors.DARK_BLUE.getIndex());
                        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
                    }
                    default -> {
                        font.setColor(IndexedColors.DARK_TEAL.getIndex());
                        style.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
                    }
                }
                style.setFont(font);
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                borders(style);
                statusStyles.put(status, style);
            }
        }

        private static void borders(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }

        CellStyle statusStyle(Status status) {
            return statusStyles.getOrDefault(status, centerStyle);
        }
    }
}
