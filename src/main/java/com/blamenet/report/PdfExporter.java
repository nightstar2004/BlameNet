package com.blamenet.report;

import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 报表导出器。
 * <p>
 * 输入是 JSON 主数据源反序列化出的 {@link Report}，本类不采集任何数据。
 * 使用 PDFBox 3.0.3 直接排版：A4 纵向、页眉页脚、章节分页、表格与代码块，
 * 并嵌入系统真实存在的中文 TrueType 字体（缺字体时明确失败，不谎报成功）。
 * <p>
 * 按测试反馈拆分为两个版本：
 * <ul>
 *     <li><b>简洁版</b>（{@code BlameNet-<编号>.pdf}）：不展示证据原文，只列出证据条目名称与元信息，
 *         适合打印与快速阅读；</li>
 *     <li><b>完整版</b>（{@code BlameNet-<编号>-full.pdf}）：展示全部证据原文，适合归档与审计。</li>
 * </ul>
 */
public class PdfExporter implements Exporter {

    /** 是否输出证据原文（完整版 true，简洁版 false）。 */
    private final boolean full;

    public PdfExporter() {
        this(false);
    }

    protected PdfExporter(boolean full) {
        this.full = full;
    }

    /** 当前是否为完整版。 */
    protected boolean isFull() {
        return full;
    }

    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN_L = 48f;
    private static final float MARGIN_R = 48f;
    private static final float MARGIN_TOP = 58f;
    private static final float MARGIN_BOTTOM = 56f;
    private static final float CONTENT_W = PAGE_W - MARGIN_L - MARGIN_R;

    private static final Color C_TEXT = new Color(0x1F, 0x23, 0x28);
    private static final Color C_MUTED = new Color(0x5B, 0x65, 0x72);
    private static final Color C_ACCENT = new Color(0x1B, 0x4B, 0x86);
    private static final Color C_RULE = new Color(0xD3, 0xDA, 0xE3);
    private static final Color C_PANEL = new Color(0xED, 0xF1, 0xF6);
    private static final Color C_CODE_BG = new Color(0xF4, 0xF6, 0xF8);
    private static final Color C_PASS = new Color(0x15, 0x73, 0x47);
    private static final Color C_WARN = new Color(0xA9, 0x5A, 0x06);
    private static final Color C_FAIL = new Color(0xB3, 0x1C, 0x1C);
    private static final Color C_MANUAL = new Color(0x5B, 0x2A, 0x9B);
    private static final Color C_INFO = new Color(0x44, 0x55, 0x66);

    /**
     * 字体来源候选（按优先级排序）。
     * <p>
     * 语法：{@code ttf:文件名} 或 {@code ttc:文件名:字体族名}。
     * 顺序依据文本抽取保真度与版式观感：部分字体（如 Deng.ttf、思源黑体部分版本）
     * 的 cmap 把「网/页/手/工/一」等常用字与康熙部首共用同一字形，导致 PDF 文本复制、
     * 检索时得到 U+2F79 之类的部首码点。因此这里优先选择经自检验证过无码点偏差的字体。
     */
    private static final String[] REGULAR_SOURCES = {
            "ttf:STSONG.TTF",
            "ttc:simsun.ttc:SimSun",
            "ttf:simfang.ttf",
            "ttf:simkai.ttf",
            "ttf:simhei.ttf",
            "ttc:msyh.ttc:Microsoft YaHei UI",
            "ttc:msyh.ttc:Microsoft YaHei",
            "ttf:Deng.ttf",
            "ttf:SourceHanSansCN-Normal.ttf"
    };

    /** 标题字体候选：优先黑体（笔画均匀、小字号下清晰）。 */
    private static final String[] BOLD_SOURCES = {
            "ttf:simhei.ttf",
            "ttc:msyhbd.ttc:Microsoft YaHei",
            "ttc:simsun.ttc:SimSun",
            "ttf:simkai.ttf",
            "ttf:STSONG.TTF"
    };

    /**
     * 字体自检样张：覆盖最容易出现码点偏差的常用字。
     * 自检方式为「写一个内存 PDF → 抽取文本 → 与样张逐字比对」，只有完全一致才采用该字体。
     */
    private static final String PROBE_SAMPLE = "网页面手工无文一行自动用说明关于类型编号证据诊断报告测试";

    /** 字体来源描述。 */
    private record FontSource(String fileName, String family) {

        static FontSource parse(String spec) {
            String[] parts = spec.split(":");
            if (parts.length >= 3) {
                return new FontSource(parts[1], parts[2]);
            }
            return new FontSource(parts[1], null);
        }

        boolean isCollection() {
            return family != null;
        }

        String describe() {
            return fileName + (family == null ? "" : "（" + family + "）");
        }
    }

    @Override
    public String id() {
        return full ? "pdf-full" : "pdf";
    }

    @Override
    public String displayName() {
        return full ? "PDF 报表（完整版）" : "PDF 报表（简洁版）";
    }

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public String manifestRole() {
        return full ? "report.pdf.full" : "report.pdf.concise";
    }

    @Override
    public String mimeType() {
        return "application/pdf";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        Path file = full ? paths.file("full", extension()) : paths.file(extension());
        FontSet fonts = new FontSet();
        try (PDDocument doc = new PDDocument()) {
            fonts.load(doc);
            applyMetadata(doc, report, paths, fonts.describe());

            PdfWriter writer = new PdfWriter(doc, fonts.regular, fonts.bold, paths.reportId());
            renderCover(writer, report, paths);
            writer.newPage();
            renderMeta(writer, report);
            renderEnvironment(this, writer, report);
            renderSummary(writer, report);
            renderModuleTable(writer, report);
            renderModules(this, writer, report);
            renderManual(writer, report);
            renderAppendix(this, writer, report, paths);
            writer.finish();

            doc.save(file.toFile());
        } finally {
            fonts.close();
        }
        return file;
    }

    // ------------------------------------------------------------------ 元数据

    private static void applyMetadata(PDDocument doc, Report report, ReportPaths paths, String fontInfo) {
        PDDocumentInformation info = new PDDocumentInformation();
        String risk = TextUtil.nvl(report.summary.riskLevel);
        info.setTitle("BlameNet 网络诊断报告 " + paths.reportId());
        info.setAuthor(BuildInfo.toolName());
        info.setSubject("Windows 网络故障分层诊断报告（只读采集，结论附原始命令与输出证据）");
        info.setKeywords("BlameNet;网络诊断;故障分层;风险等级=" + risk + ";报告编号=" + paths.reportId());
        info.setCreator(BuildInfo.toolName() + " " + BuildInfo.toolVersion());
        info.setProducer("Apache PDFBox 3.0.3 ｜ 嵌入字体 " + fontInfo);
        String start = report.testMeta == null ? null : report.testMeta.testStartTime;
        if (TextUtil.isNotBlank(start)) {
            try {
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(java.time.Instant.parse(start).toEpochMilli());
                info.setCreationDate(calendar);
            } catch (Exception ignored) {
                // 时间戳解析失败时保留 PDFBox 默认创建时间，不伪造。
            }
        }
        doc.setDocumentInformation(info);
    }

    // ------------------------------------------------------------------ 章节

    private static void renderCover(PdfWriter w, Report report, ReportPaths paths) throws IOException {
        float y = PAGE_H - 150f;
        w.text("BlameNet 网络诊断报告", w.bold, 25f, MARGIN_L, y, C_ACCENT);
        y -= 28f;
        w.text(BuildInfo.toolName() + " " + BuildInfo.toolVersion()
                + " · Windows 网络故障分层诊断（只读采集，不修改任何系统配置）",
                w.regular, 10f, MARGIN_L, y, C_MUTED);
        y -= 16f;
        w.hline(MARGIN_L, y, PAGE_W - MARGIN_R, C_ACCENT, 1.6f, null);
        y -= 34f;

        Map<String, String> cover = new LinkedHashMap<>();
        cover.put("报告编号", paths.reportId());
        cover.put("生成时间", TimeUtil.human(java.time.Instant.now()));
        cover.put("测试开始时间", report.testMeta.testStartTime);
        cover.put("测试结束时间", report.testMeta.testEndTime);
        cover.put("测试总耗时", report.testMeta.testDuration);
        cover.put("测试状态（源值）", report.testMeta.status);
        cover.put("管理员权限（源值）", String.valueOf(report.testMeta.adminPrivilege));
        cover.put("主机名", report.testEnvironment.hostName);
        cover.put("操作系统", report.testEnvironment.osCaption);
        cover.put("风险等级", RiskLevel.fromSource(report.summary.riskLevel).chinese()
                + "（源值 " + TextUtil.nvl(report.summary.riskLevel) + "）");
        cover.put("模块总数", String.valueOf(report.summary.totalModuleCount));
        cover.put("结论统计", "通过 " + report.summary.passCount
                + " · 警告 " + report.summary.warnCount
                + " · 失败 " + report.summary.failCount
                + " · 手动 " + report.summary.manualCount
                + " · 信息 " + report.summary.infoCount);
        cover.put("证据条目总数", String.valueOf(report.evidenceCount()));
        y = w.keyValues(cover, y, 104f, 10f, 7f);

        y -= 24f;
        float boxH = 96f;
        w.panel(MARGIN_L, y - boxH, CONTENT_W, boxH, C_PANEL);
        float ty = y - 20f;
        w.text("全局诊断", w.bold, 11f, MARGIN_L + 14f, ty, C_ACCENT);
        ty -= 17f;
        ty = w.paragraph(TextUtil.nvl(report.summary.globalDiagnosis), w.regular, 9.5f,
                MARGIN_L + 14f, ty, CONTENT_W - 28f, C_TEXT, 13.5f);
        y -= boxH + 18f;

        String layers = report.summary.faultLayers == null || report.summary.faultLayers.isEmpty()
                ? "未定位到明确的故障层（或本机未发现故障）"
                : TextUtil.join(report.summary.faultLayers, "；");
        w.text("故障分层定位：" + layers, w.regular, 9.5f, MARGIN_L, y, C_TEXT);
    }

    private static void renderMeta(PdfWriter w, Report report) throws IOException {
        w.heading(1, "一、测试元数据");
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("TestStartTime", report.testMeta.testStartTime);
        rows.put("TestEndTime", report.testMeta.testEndTime);
        rows.put("TestDuration", report.testMeta.testDuration);
        rows.put("Status", report.testMeta.status);
        rows.put("AdminPrivilege", String.valueOf(report.testMeta.adminPrivilege));
        rows.put("ReportId", report.testMeta.reportId);
        rows.put("Tool", report.testMeta.tool);
        rows.put("ToolVersion", report.testMeta.toolVersion);
        rows.put("Schema", report.testMeta.schema);
        rows.put("TimeZone", report.testMeta.timeZone);
        rows.put("Locale", report.testMeta.locale);
        rows.put("JavaVersion", report.testMeta.javaVersion);
        rows.put("BuildTool", report.testMeta.buildTool);
        rows.put("RunProfile", report.testMeta.runProfile);
        rows.put("ModuleFilter", report.testMeta.moduleFilter);
        rows.put("InstallDirectory", report.testMeta.installDirectory);
        rows.put("ResultsDirectory", report.testMeta.resultsDirectory);
        w.line("上表字段名与 JSON 源数据完全一致（源数据层不做翻译改写）。", w.regular, 8.5f, MARGIN_L, C_MUTED);
        w.gap(2f);
        w.keyValues(rows, w.peek(), 150f, 9.5f, 6f);
        w.gap(10f);
    }

    private static void renderEnvironment(PdfExporter exporter, PdfWriter w, Report report) throws IOException {
        w.heading(1, "二、测试环境与预检证据");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("主机名 / 用户", TextUtil.nvl(report.testEnvironment.hostName) + " / "
                + TextUtil.nvl(report.testEnvironment.userName));
        env.put("域 / 工作组", report.testEnvironment.domain);
        env.put("厂商 / 型号", TextUtil.nvl(report.testEnvironment.manufacturer) + " / "
                + TextUtil.nvl(report.testEnvironment.model));
        env.put("操作系统", TextUtil.nvl(report.testEnvironment.osCaption) + "（"
                + TextUtil.nvl(report.testEnvironment.osVersion) + "，Build "
                + TextUtil.nvl(report.testEnvironment.osBuild) + "，"
                + TextUtil.nvl(report.testEnvironment.osArchitecture) + "）");
        env.put("系统盘 / 物理内存", TextUtil.nvl(report.testEnvironment.systemDrive) + " / "
                + (report.testEnvironment.totalMemoryMb == null ? "" : report.testEnvironment.totalMemoryMb + " MB"));
        env.put("时区偏移（分钟）", report.testEnvironment.timeZoneOffsetMinutes == null ? ""
                : String.valueOf(report.testEnvironment.timeZoneOffsetMinutes));
        env.put("预检命令条数", report.testEnvironment.probeCommandCount == null ? ""
                : String.valueOf(report.testEnvironment.probeCommandCount));
        env.put("数据源说明", report.testEnvironment.dataSources);
        w.keyValues(env, w.peek(), 118f, 9.5f, 6f);

        w.heading(3, "关键工具可用性");
        if (report.testEnvironment.toolAvailability.isEmpty()) {
            w.line("未记录到工具可用性信息。", w.regular, 9.5f, MARGIN_L, C_MUTED);
            w.gap(6f);
        } else {
            w.table(new String[]{"工具", "可执行文件真实路径"}, toRows(report.testEnvironment.toolAvailability),
                    new float[]{1f, 3.2f}, 9f);
        }

        w.heading(3, "采集外壳");
        if (report.testEnvironment.collectorShell.isEmpty()) {
            w.line("未记录到采集外壳信息。", w.regular, 9.5f, MARGIN_L, C_MUTED);
            w.gap(6f);
        } else {
            w.table(new String[]{"项目", "值"}, toRows(report.testEnvironment.collectorShell),
                    new float[]{1f, 3.2f}, 9f);
        }

        Evidence admin = report.testEnvironment.adminCheckEvidence;
        if (admin != null) {
            w.heading(3, "管理员权限判定证据");
            w.kv("证据名称", admin.evidenceName, 78f, 9.5f);
            w.kv("命令", admin.rawCommand, 78f, 9.5f);
            w.kv("记录时间", admin.recordTime, 78f, 9.5f);
            if (exporter.isFull()) {
                w.code(admin.rawOutput, 8f, 260f);
            } else {
                w.line("简洁版不展示证据原文，完整内容见完整版 PDF 或 JSON 主数据源。", w.regular, 8.5f,
                        MARGIN_L + 12f, C_MUTED);
            }
            w.gap(8f);
        }
    }

    private static void renderSummary(PdfWriter w, Report report) throws IOException {
        w.heading(1, "三、摘要");
        w.table(new String[]{"统计项", "源字段", "数值", "展示值"},
                List.of(
                        new String[]{"模块总数", "TotalModuleCount", String.valueOf(report.summary.totalModuleCount), "—"},
                        new String[]{"通过", "PassCount", String.valueOf(report.summary.passCount), "通过"},
                        new String[]{"警告", "WarnCount", String.valueOf(report.summary.warnCount), "警告"},
                        new String[]{"失败", "FailCount", String.valueOf(report.summary.failCount), "失败"},
                        new String[]{"手动", "ManualCount", String.valueOf(report.summary.manualCount), "手动"},
                        new String[]{"信息", "InfoCount", String.valueOf(report.summary.infoCount), "信息"}),
                new float[]{1f, 1.6f, 0.8f, 1.2f}, 9.5f);

        w.kv("风险等级", RiskLevel.fromSource(report.summary.riskLevel).chinese()
                + "（源值 " + TextUtil.nvl(report.summary.riskLevel) + "）", 78f, 9.5f);
        w.kv("全局诊断", report.summary.globalDiagnosis, 78f, 9.5f);
        w.kv("故障分层", TextUtil.join(report.summary.faultLayers, "；"), 78f, 9.5f);
        w.kv("失败模块", listText(report.summary.failingModules), 78f, 9.5f);
        w.kv("警告模块", listText(report.summary.warningModules), 78f, 9.5f);
        w.kv("手动验证模块", listText(report.summary.manualModules), 78f, 9.5f);
        w.kv("信息类模块", listText(report.summary.infoModules), 78f, 9.5f);
        w.kv("报告生成时间", report.summary.reportGeneratedAt, 78f, 9.5f);
        w.kv("主数据源", report.summary.dataSourceJson, 78f, 9.5f);
        w.gap(10f);
    }

    private static void renderModuleTable(PdfWriter w, Report report) throws IOException {
        w.heading(1, "四、模块总表");
        List<String[]> rows = new ArrayList<>();
        for (ModuleResult m : report.moduleResults) {
            Status status = m.statusEnum();
            rows.add(new String[]{
                    TextUtil.nvl(m.moduleId),
                    TextUtil.nvl(m.moduleName),
                    TextUtil.nvl(m.status),
                    status.chinese(),
                    TextUtil.nvl(m.duration),
                    String.valueOf(m.evidences == null ? 0 : m.evidences.size()),
                    Boolean.TRUE.equals(m.manualVerified) ? "已回填" : (m.manualTask == null ? "—" : "待回填")
            });
        }
        w.tableColored(new String[]{"编号", "模块名称", "状态源值", "展示", "耗时", "证据", "手动"},
                rows, new float[]{0.42f, 2.42f, 0.66f, 0.5f, 0.78f, 0.45f, 0.6f}, 8.6f, 3);
        w.gap(10f);
    }

    private static void renderModules(PdfExporter exporter, PdfWriter w, Report report) throws IOException {
        w.heading(1, "五、模块详情");
        for (ModuleResult m : report.moduleResults) {
            Status status = m.statusEnum();
            w.heading(2, "模块 " + TextUtil.nvl(m.moduleId) + "　" + TextUtil.nvl(m.moduleName));
            w.line("状态：" + status.chinese() + "（源值 " + TextUtil.nvl(m.status) + "）"
                            + "　·　分类：" + TextUtil.nvl(m.category)
                            + "　·　耗时：" + TextUtil.nvl(m.duration)
                            + "　·　命令条数：" + m.executedCommandCount
                            + (m.requiresAdmin ? "　·　需要管理员权限" : ""),
                    w.regular, 8.6f, MARGIN_L, statusColor(status));
            w.gap(3f);
            w.kv("结论摘要", m.summary, 70f, 9.5f);
            w.kv("根因推断", m.diagnosis, 70f, 9.5f);
            w.kv("修复建议", m.suggestion, 70f, 9.5f);
            if (m.manualVerified != null || TextUtil.isNotBlank(m.manualNote)) {
                w.kv("人工回填", (Boolean.TRUE.equals(m.manualVerified) ? "已验证" : "未验证")
                        + (TextUtil.isNotBlank(m.manualNote) ? "：" + m.manualNote : ""), 70f, 9.5f);
            }
            if (m.manualTask != null) {
                w.kv("外部验证任务", manualText(m.manualTask), 70f, 9.5f);
            }
            List<Evidence> evidences = m.evidences == null ? List.of() : m.evidences;
            if (evidences.isEmpty()) {
                w.line("本模块未产生证据条目。", w.regular, 8.6f, MARGIN_L + 12f, C_MUTED);
                w.gap(4f);
            } else if (!exporter.isFull()) {
                renderEvidenceList(w, evidences);
            } else {
                renderEvidenceFull(w, evidences);
            }
            w.rule();
            w.gap(9f);
        }
    }

    /** 简洁版：证据只列条目名称与元信息，不展示命令与输出原文。 */
    private static void renderEvidenceList(PdfWriter w, List<Evidence> evidences) throws IOException {
        w.line("证据链（" + evidences.size() + " 条，简洁版仅列出条目；完整原文见完整版 PDF 或 JSON 主数据源）",
                w.bold, 9f, MARGIN_L + 12f, C_ACCENT);
        w.gap(2f);
        int index = 1;
        for (Evidence e : evidences) {
            w.line("证据 " + index++ + "　" + TextUtil.nvl(e.evidenceName)
                            + "　·　记录时间 " + TextUtil.nvl(e.recordTime)
                            + "　·　退出码 " + (e.exitCode == null ? "—" : e.exitCode)
                            + (Boolean.TRUE.equals(e.truncated)
                            ? "　·　输出已截断（原始长度 " + e.originalOutputLength + " 字符）" : ""),
                    w.regular, 8.4f, MARGIN_L + 20f, C_TEXT);
        }
        w.gap(5f);
    }

    /** 完整版：逐条展示命令、元信息与输出原文（全量，跨页输出，不做版式折叠）。 */
    private static void renderEvidenceFull(PdfWriter w, List<Evidence> evidences) throws IOException {
        w.line("证据链（" + evidences.size() + " 条）", w.bold, 9.5f, MARGIN_L + 12f, C_ACCENT);
        w.gap(2f);
        int index = 1;
        for (Evidence e : evidences) {
            w.line("证据 " + index++ + "　" + TextUtil.nvl(e.evidenceName), w.bold, 9f, MARGIN_L + 20f, C_TEXT);
            w.gap(1f);
            w.kvIndent("原始命令", e.rawCommand, 70f, 8.6f, 20f, C_TEXT, true);
            w.line("记录时间 " + TextUtil.nvl(e.recordTime)
                            + "　退出码 " + (e.exitCode == null ? "—" : e.exitCode)
                            + "　耗时 " + (e.durationMillis == null ? "—" : e.durationMillis + " ms")
                            + "　超时 " + (e.timedOut == null ? "—" : e.timedOut)
                            + "　截断 " + (e.truncated == null ? "—" : e.truncated),
                    w.regular, 8f, MARGIN_L + 20f, C_MUTED);
            w.gap(2f);
            // 完整版：输出原文全量展示（含对应 JSON 主数据源中的全部内容），跨页续排，不按版式折叠。
            w.code(e.rawOutput, 7.6f, Float.MAX_VALUE);
            w.gap(6f);
        }
    }

    private static void renderManual(PdfWriter w, Report report) throws IOException {
        w.heading(1, "六、手动验证清单（共 " + report.manualValidationList.size() + " 项）");
        if (report.manualValidationList.isEmpty()) {
            w.line("本次诊断未产生外部验证任务。", w.regular, 9.5f, MARGIN_L, C_MUTED);
            w.gap(6f);
            return;
        }
        int index = 1;
        for (ManualValidationItem item : report.manualValidationList) {
            w.heading(2, "任务 " + index + "　模块 " + TextUtil.nvl(item.moduleId) + "　" + TextUtil.nvl(item.taskTitle));
            w.kv("优先级", item.priority, 70f, 9.5f);
            w.kv("操作说明", item.instructions, 70f, 9.5f);
            w.kv("用户填写结果", item.isFilled() ? item.userFillResult : "待填写", 70f, 9.5f);
            w.kv("回填时间", item.filledAt, 70f, 9.5f);
            index++;
        }
        w.gap(6f);
    }

    private static void renderAppendix(PdfExporter exporter, PdfWriter w, Report report, ReportPaths paths) throws IOException {
        w.heading(1, "七、声明");

        w.heading(3, "证据说明");
        w.bullet("每个模块结论均附带真实执行过的命令、原始输出与记录时间，除超长截断外不做任何改写。");
        w.bullet("本工具为只读诊断：不执行 ipconfig /release、/renew、/flushdns、netsh reset、sfc /scannow 修复等任何变更类操作。");
        w.bullet("无法在当前系统内完成的检查（物理替换、干净启动、PE 对比、重装建议）不跳过，而是列入手动验证清单。");

        w.heading(3, "隐私与脱敏提示");
        w.bullet("报告可能包含主机名、用户名、MAC 地址、IP 地址与网络拓扑信息，分享前请自行评估并脱敏。");
        w.bullet("结果文件仅写入程序安装目录下的 results 目录，不上传任何服务器。");

        w.heading(3, "校验与完整性");
        w.bullet("manifest.json 中的 SHA-256 由本机运行时真实计算，可用于核验文件是否被篡改；包内文件清单见 README.txt 与 manifest.json。");
        w.bullet("报告编号：" + paths.reportId()
                + "；主数据源：" + report.summary.dataSourceJson
                + "；生成时间：" + report.summary.reportGeneratedAt);

        w.heading(3, "免责声明");
        w.bullet("本报告基于自动化采集结果给出诊断与建议，修复动作需由管理员自行确认后执行。");
        w.bullet("因未按建议顺序执行、或在采集期间修改系统配置导致的结论偏差，本工具不承担责任。");
    }

    // ------------------------------------------------------------------ 渲染辅助

    private static List<String[]> toRows(Map<String, String> map) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            rows.add(new String[]{e.getKey(), e.getValue()});
        }
        return rows;
    }

    private static String listText(List<String> list) {
        return list == null || list.isEmpty() ? "无" : TextUtil.join(list, "；");
    }

    private static String manualText(ManualValidationItem item) {
        return TextUtil.nvl(item.taskTitle) + "：" + TextUtil.nvl(item.instructions)
                + "（" + (item.isFilled() ? "已回填：" + item.userFillResult : "待回填") + "）";
    }

    private static Color statusColor(Status status) {
        return switch (status) {
            case PASS -> C_PASS;
            case WARN -> C_WARN;
            case FAIL -> C_FAIL;
            case MANUAL -> C_MANUAL;
            case INFO -> C_INFO;
        };
    }

    // ------------------------------------------------------------------ 字体

    /**
     * 字体集合：真实加载系统字体，并做文本抽取保真自检；
     * 找不到任何可用字体时抛错（不静默降级成乱码）。
     */
    private static final class FontSet implements Closeable {

        private PDType0Font regular;
        private PDType0Font bold;
        private String regularName = "（未加载）";
        private String boldName = "（未加载）";
        private final List<Closeable> resources = new ArrayList<>();

        void load(PDDocument doc) throws IOException {
            List<String> tried = new ArrayList<>();
            regular = pick(doc, REGULAR_SOURCES, tried, true);
            bold = pick(doc, BOLD_SOURCES, tried, false);
            if (regular == null) {
                throw new IOException("未在系统中找到可用的中文 TrueType 字体，无法生成 PDF 报表。已尝试："
                        + String.join("、", tried));
            }
            if (bold == null) {
                bold = regular;
                boldName = regularName;
            }
        }

        /** 字体启用情况（用于 PDF 元数据与排查）。 */
        String describe() {
            return regularName + " / " + boldName;
        }

        /**
         * 按优先级挑选字体：文件存在 → 可加载 → 抽取保真自检通过。
         * <p>
         * 若所有候选都无法通过自检（例如精简系统只装了雅黑），则退回第一个可加载字体，
         * 保证报表仍能生成（字形渲染正确，仅复制文本时部分字符可能取到部首码点）。
         */
        private PDType0Font pick(PDDocument doc, String[] sources, List<String> tried, boolean regularRole) {
            for (String spec : sources) {
                FontSource source = FontSource.parse(spec);
                if (fontFile(source.fileName()) == null) {
                    continue;
                }
                tried.add(source.describe());
                if (!extractionSafe(source)) {
                    continue;
                }
                try {
                    PDType0Font font = loadSource(doc, source, resources);
                    if (regularRole) {
                        regularName = source.describe();
                    } else {
                        boldName = source.describe();
                    }
                    return font;
                } catch (Exception e) {
                    tried.add(source.describe() + "（加载失败：" + e.getMessage() + "）");
                }
            }
            // 自检全部未通过：退回第一个可加载字体，保证 PDF 仍可生成（字形渲染正确，
            // 仅复制文本时个别常用字可能取到部首码点），并在元数据中如实标注。
            for (String spec : sources) {
                FontSource source = FontSource.parse(spec);
                if (fontFile(source.fileName()) == null) {
                    continue;
                }
                try {
                    PDType0Font font = loadSource(doc, source, resources);
                    if (regularRole) {
                        regularName = source.describe() + "（未通过文本抽取自检）";
                    } else {
                        boldName = source.describe() + "（未通过文本抽取自检）";
                    }
                    return font;
                } catch (Exception ignored) {
                    // 继续尝试下一个候选。
                }
            }
            return null;
        }

        /** 从指定来源加载字体；keepOpen 用于保存输入流 / 字体集合的引用，待 PDF 保存后再关闭。 */
        private PDType0Font loadSource(PDDocument doc, FontSource source, List<Closeable> keepOpen)
                throws IOException {
            Path path = fontFile(source.fileName());
            if (path == null) {
                throw new IOException("字体文件不存在：" + source.fileName());
            }
            if (!source.isCollection()) {
                InputStream in = new BufferedInputStream(Files.newInputStream(path));
                try {
                    PDType0Font font = PDType0Font.load(doc, in, true);
                    if (keepOpen != null) {
                        keepOpen.add(in);
                    } else {
                        in.close();
                    }
                    return font;
                } catch (IOException e) {
                    in.close();
                    throw e;
                }
            }
            TrueTypeCollection collection = new TrueTypeCollection(path.toFile());
            try {
                TrueTypeFont font = collection.getFontByName(source.family());
                if (font == null) {
                    collection.close();
                    throw new IOException("字体集合 " + source.fileName() + " 中未找到字体族 " + source.family());
                }
                PDType0Font loaded = PDType0Font.load(doc, font, true);
                if (keepOpen != null) {
                    keepOpen.add(collection);
                } else {
                    collection.close();
                }
                return loaded;
            } catch (IOException | RuntimeException e) {
                try {
                    collection.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响主流程。
                }
                throw e;
            }
        }

        /**
         * 文本抽取保真自检：把样张写进一个内存 PDF，再用 PDFBox 抽取文本逐字比对。
         * 只有完全一致才认为该字体不会在复制、检索时产生码点偏差。
         */
        private static boolean extractionSafe(FontSource source) {
            List<Closeable> temp = new ArrayList<>();
            try (PDDocument probe = new PDDocument()) {
                PDType0Font font = loadSourceStatic(probe, source, temp);
                PDPage page = new PDPage(PDRectangle.A4);
                probe.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(probe, page)) {
                    cs.beginText();
                    cs.setFont(font, 12f);
                    cs.newLineAtOffset(40f, 700f);
                    cs.showText(PROBE_SAMPLE);
                    cs.endText();
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                probe.save(buffer);
                try (PDDocument check = org.apache.pdfbox.Loader.loadPDF(buffer.toByteArray())) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(check)
                            .replaceAll("\\s+", "");
                    return PROBE_SAMPLE.equals(text);
                }
            } catch (Exception e) {
                return false;
            } finally {
                for (Closeable resource : temp) {
                    try {
                        resource.close();
                    } catch (IOException ignored) {
                        // 探测用的资源关闭失败不影响主流程。
                    }
                }
            }
        }

        private static PDType0Font loadSourceStatic(PDDocument doc, FontSource source, List<Closeable> keepOpen)
                throws IOException {
            Path path = fontFile(source.fileName());
            if (path == null) {
                throw new IOException("字体文件不存在：" + source.fileName());
            }
            if (!source.isCollection()) {
                InputStream in = new BufferedInputStream(Files.newInputStream(path));
                PDType0Font font = PDType0Font.load(doc, in, true);
                keepOpen.add(in);
                return font;
            }
            TrueTypeCollection collection = new TrueTypeCollection(path.toFile());
            keepOpen.add(collection);
            TrueTypeFont font = collection.getFontByName(source.family());
            if (font == null) {
                throw new IOException("字体集合 " + source.fileName() + " 中未找到字体族 " + source.family());
            }
            return PDType0Font.load(doc, font, true);
        }

        private static Path fontFile(String name) {
            List<Path> roots = new ArrayList<>();
            String windir = System.getenv("WINDIR");
            if (TextUtil.isNotBlank(windir)) {
                roots.add(Path.of(windir, "Fonts"));
            }
            roots.add(Path.of("C:\\Windows", "Fonts"));
            String localAppData = System.getenv("LOCALAPPDATA");
            if (TextUtil.isNotBlank(localAppData)) {
                roots.add(Path.of(localAppData, "Microsoft", "Windows", "Fonts"));
            }
            for (Path root : roots) {
                Path candidate = root.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            for (Closeable resource : resources) {
                resource.close();
            }
            resources.clear();
        }
    }

    // ------------------------------------------------------------------ 版式引擎

    /** 极简但真实的 PDF 版式引擎：分页、页眉页脚、段落换行、表格、代码块。 */
    private static final class PdfWriter {

        private static final float LINE_FACTOR = 1.62f;

        private final PDDocument doc;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final String reportId;
        private final List<PDPage> pages = new ArrayList<>();
        private final Map<Integer, Boolean> glyphCache = new HashMap<>();
        private final Map<String, Float> widthCache = new HashMap<>();

        private PDPageContentStream cs;
        private float y;

        PdfWriter(PDDocument doc, PDType0Font regular, PDType0Font bold, String reportId) throws IOException {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.reportId = reportId;
            newPage();
        }

        float peek() {
            return y;
        }

        // ---------------------------------------------------------- 页面

        void newPage() throws IOException {
            closeStream();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = PAGE_H - MARGIN_TOP;
            if (pages.size() > 1) {
                drawHeader();
            }
        }

        private void drawHeader() throws IOException {
            String text = "BlameNet 网络诊断报告 | " + reportId;
            float width = width(text, regular, 8f);
            draw(text, regular, 8f, PAGE_W - MARGIN_R - width, PAGE_H - 40f, C_MUTED);
            hline(MARGIN_L, PAGE_H - 48f, PAGE_W - MARGIN_R, C_RULE, 0.6f, null);
        }

        private void closeStream() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        void finish() throws IOException {
            closeStream();
            int total = pages.size();
            for (int i = 0; i < total; i++) {
                PDPage page = pages.get(i);
                try (PDPageContentStream footer = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.setStrokingColor(C_RULE);
                    footer.setLineWidth(0.6f);
                    footer.moveTo(MARGIN_L, MARGIN_BOTTOM - 12f);
                    footer.lineTo(PAGE_W - MARGIN_R, MARGIN_BOTTOM - 12f);
                    footer.stroke();
                    float baseline = MARGIN_BOTTOM - 24f;
                    drawTo(footer, reportId, regular, 8f, MARGIN_L, baseline, C_MUTED);
                    String center = "第 " + (i + 1) + " 页 / 共 " + total + " 页";
                    float cw = width(center, regular, 8f);
                    drawTo(footer, center, regular, 8f, (PAGE_W - cw) / 2f, baseline, C_MUTED);
                    String right = BuildInfo.toolName() + " " + BuildInfo.toolVersion();
                    float rw = width(right, regular, 8f);
                    drawTo(footer, right, regular, 8f, PAGE_W - MARGIN_R - rw, baseline, C_MUTED);
                }
            }
        }

        // ---------------------------------------------------------- 基础绘制

        void text(String value, PDType0Font font, float size, float x, float baseline, Color color)
                throws IOException {
            draw(value, font, size, x, baseline, color);
        }

        /**
         * 绘制一行文本并按真实行高推进光标。
         * <p>
         * 行高按字号 × {@link #LINE_FACTOR} 计算；所有「独占一行」的内容都应使用本方法，
         * 以保证前后两行不会叠写。
         */
        void line(String value, PDType0Font font, float size, float x, Color color) throws IOException {
            float lineHeight = size * LINE_FACTOR;
            ensure(lineHeight);
            draw(value, font, size, x, y, color);
            y -= lineHeight;
        }

        private void draw(String value, PDType0Font font, float size, float x, float baseline, Color color)
                throws IOException {
            drawTo(cs, value, font, size, x, baseline, color);
        }

        private void drawTo(PDPageContentStream stream, String value, PDType0Font font, float size,
                            float x, float baseline, Color color) throws IOException {
            String safe = sanitize(value, font);
            if (safe.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(safe);
            stream.endText();
        }

        void hline(float x1, float y1, float x2, Color color, float width, PDPageContentStream target)
                throws IOException {
            PDPageContentStream stream = target == null ? cs : target;
            stream.setStrokingColor(color);
            stream.setLineWidth(width);
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y1);
            stream.stroke();
        }

        void rule() throws IOException {
            hline(MARGIN_L, y, PAGE_W - MARGIN_R, C_RULE, 0.6f, null);
        }

        void panel(float x, float bottom, float width, float height, Color color) throws IOException {
            cs.setNonStrokingColor(color);
            cs.addRect(x, bottom, width, height);
            cs.fill();
        }

        void gap(float height) throws IOException {
            y -= height;
            if (y < MARGIN_BOTTOM + 8f) {
                newPage();
            }
        }

        private void ensure(float height) throws IOException {
            if (y - height < MARGIN_BOTTOM) {
                newPage();
            }
        }

        // ---------------------------------------------------------- 结构块

        void heading(int level, String title) throws IOException {
            float size = switch (level) {
                case 1 -> 15f;
                case 2 -> 11.8f;
                default -> 10.2f;
            };
            if (level == 1) {
                gap(16f);
                ensure(size + 26f);
            } else {
                gap(9f);
                ensure(size + 16f);
            }
            draw(title, bold, size, MARGIN_L, y, C_ACCENT);
            y -= size * LINE_FACTOR;
            if (level == 1) {
                hline(MARGIN_L, y - 1f, PAGE_W - MARGIN_R, C_ACCENT, 1.1f, null);
                y -= 9f;
            }
        }

        void bullet(String value) throws IOException {
            float baseline = y;
            draw("·", bold, 9.5f, MARGIN_L + 4f, baseline, C_ACCENT);
            paragraph(value, regular, 9.5f, MARGIN_L + 16f, baseline, CONTENT_W - 16f, C_TEXT, 13.5f);
        }

        /** 返回落笔后的新 y（用于逐段推进的自定义块）。 */
        float paragraph(String value, PDType0Font font, float size, float x, float baseline,
                        float maxWidth, Color color, float lineHeight) throws IOException {
            if (TextUtil.isBlank(value)) {
                return baseline - lineHeight;
            }
            List<String> lines = wrap(value, font, size, maxWidth);
            float current = baseline;
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    ensure(lineHeight);
                    current = y;
                }
                draw(lines.get(i), font, size, x, current, color);
                if (i < lines.size() - 1) {
                    y = current - lineHeight;
                }
            }
            y = current - lineHeight;
            return y;
        }

        float keyValues(Map<String, String> map, float startY, float labelWidth, float size, float gapAfter)
                throws IOException {
            y = startY;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                kv(entry.getKey(), entry.getValue(), labelWidth, size);
            }
            gap(gapAfter);
            return y;
        }

        void kv(String label, String value, float labelWidth, float size) throws IOException {
            kvIndent(label, value, labelWidth, size, 0f, C_TEXT, false);
        }

        void kvIndent(String label, String value, float labelWidth, float size, float indent,
                      Color valueColor, boolean mono) throws IOException {
            String text = TextUtil.isBlank(value) ? "—" : value;
            PDType0Font valueFont = mono ? bold : regular;
            float lineHeight = size * LINE_FACTOR;
            float valueX = MARGIN_L + indent + labelWidth;
            float maxWidth = CONTENT_W - indent - labelWidth;
            List<String> lines = wrap(text, valueFont, size, maxWidth);
            PDType0Font labelFont = bold;
            for (int i = 0; i < lines.size(); i++) {
                ensure(lineHeight);
                float baseline = y;
                if (i == 0) {
                    draw(safeLabel(label), labelFont, size, MARGIN_L + indent, baseline, C_MUTED);
                }
                draw(lines.get(i), valueFont, size, valueX, baseline, valueColor);
                y = baseline - lineHeight;
            }
        }

        private static String safeLabel(String label) {
            return TextUtil.isBlank(label) ? "—" : label;
        }

        /** 代码块：逐行绘制浅色底纹，超宽自动换行，支持最大像素高度（超出自动分页）。 */
        void code(String text, float size, float maxHeight) throws IOException {
            String normalized = TextUtil.nvl(text).replace("\r\n", "\n").replace('\r', '\n');
            String[] raw = normalized.split("\n", -1);
            float lineHeight = size * 1.45f;
            float drawn = 0f;
            boolean first = true;
            for (String line : raw) {
                String expanded = line.replace("\t", "    ");
                List<String> parts = expanded.isEmpty() ? List.of("") : wrapNoTrim(expanded, regular, size, CONTENT_W - 20f);
                for (String part : parts) {
                    if (drawn + lineHeight > maxHeight) {
                        ensure(lineHeight);
                        draw("……（此处按版式折叠，完整原文见 JSON / HTML）", regular, size, MARGIN_L + 8f, y, C_MUTED);
                        y -= lineHeight;
                        return;
                    }
                    ensure(lineHeight);
                    if (!part.isEmpty()) {
                        panel(MARGIN_L + 4f, y - size * 0.32f, CONTENT_W - 4f, lineHeight, C_CODE_BG);
                    }
                    draw(part, regular, size, MARGIN_L + 8f, y, C_TEXT);
                    y -= lineHeight;
                    drawn += lineHeight;
                }
                if (first) {
                    first = false;
                }
            }
            if (raw.length > 0) {
                y -= 2f;
            }
        }

        void table(String[] headers, List<String[]> rows, float[] weights, float size) throws IOException {
            tableColored(headers, rows, weights, size, -1);
        }

        /** 表格渲染；highlightColumn >= 0 时该列按状态配色（参数为列下标，配合中文状态列使用）。 */
        void tableColored(String[] headers, List<String[]> rows, float[] weights, float size,
                          int highlightColumn) throws IOException {
            float total = 0f;
            for (float weight : weights) {
                total += weight;
            }
            float[] widths = new float[weights.length];
            for (int i = 0; i < weights.length; i++) {
                widths[i] = CONTENT_W * weights[i] / total;
            }
            float pad = 4f;
            float lineHeight = size * 1.45f;
            float headerHeight = lineHeight + 8f;

            ensure(headerHeight + lineHeight);
            drawHeaderRow(headers, widths, size, pad, lineHeight, headerHeight);

            for (String[] row : rows) {
                List<List<String>> cells = new ArrayList<>();
                int maxLines = 1;
                for (int i = 0; i < headers.length; i++) {
                    String value = i < row.length ? TextUtil.nvl(row[i]) : "";
                    List<String> lines = wrapNoTrim(value, regular, size, widths[i] - pad * 2f);
                    cells.add(lines);
                    maxLines = Math.max(maxLines, lines.size());
                }
                float rowHeight = maxLines * lineHeight + 7f;
                if (y - rowHeight < MARGIN_BOTTOM) {
                    newPage();
                    drawHeaderRow(headers, widths, size, pad, lineHeight, headerHeight);
                }
                float top = y;
                for (int i = 0; i < cells.size(); i++) {
                    float x = MARGIN_L + offset(widths, i) + pad;
                    float baseline = top - 4f - size;
                    Color color = C_TEXT;
                    if (i == highlightColumn && !cells.get(i).isEmpty()) {
                        color = statusColor(Status.fromChinese(joinLines(cells.get(i))));
                    }
                    for (String cellLine : cells.get(i)) {
                        draw(cellLine, regular, size, x, baseline, color);
                        baseline -= lineHeight;
                    }
                }
                y = top - rowHeight;
                hline(MARGIN_L, y, PAGE_W - MARGIN_R, C_RULE, 0.5f, null);
            }
            y -= 4f;
        }

        private void drawHeaderRow(String[] headers, float[] widths, float size, float pad,
                                   float lineHeight, float headerHeight) throws IOException {
            y -= headerHeight;
            panel(MARGIN_L, y, CONTENT_W, headerHeight, C_PANEL);
            float baseline = y + headerHeight - 4f - size;
            for (int i = 0; i < headers.length; i++) {
                draw(headers[i], bold, size, MARGIN_L + offset(widths, i) + pad, baseline, C_ACCENT);
            }
            hline(MARGIN_L, y, PAGE_W - MARGIN_R, C_RULE, 0.6f, null);
        }

        private static float offset(float[] widths, int index) {
            float x = 0f;
            for (int i = 0; i < index; i++) {
                x += widths[i];
            }
            return x;
        }

        private static String joinLines(List<String> lines) {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line.trim());
            }
            return sb.toString();
        }

        // ---------------------------------------------------------- 文本度量

        float width(String value, PDType0Font font, float size) {
            String safe = sanitize(value, font);
            if (safe.isEmpty()) {
                return 0f;
            }
            String key = System.identityHashCode(font) + "|" + safe;
            Float base = widthCache.get(key);
            if (base == null) {
                try {
                    base = font.getStringWidth(safe) / 1000f;
                } catch (IOException e) {
                    base = safe.length() * 0.5f;
                }
                widthCache.put(key, base);
            }
            return base * size;
        }

        /** 按码点折行（CJK 逐字断行，ASCII 尽量按空格断行）。 */
        List<String> wrap(String value, PDType0Font font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            if (TextUtil.isBlank(value)) {
                return lines;
            }
            for (String hard : value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                lines.addAll(wrapLine(hard, font, size, maxWidth, true));
            }
            return lines;
        }

        List<String> wrapNoTrim(String value, PDType0Font font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            String normalized = TextUtil.nvl(value).replace("\r\n", "\n").replace('\r', '\n');
            for (String hard : normalized.split("\n", -1)) {
                lines.addAll(wrapLine(hard, font, size, maxWidth, false));
            }
            return lines;
        }

        private List<String> wrapLine(String line, PDType0Font font, float size, float maxWidth, boolean trim) {
            List<String> result = new ArrayList<>();
            if (line == null || line.isEmpty()) {
                result.add("");
                return result;
            }
            if (maxWidth <= 1f) {
                maxWidth = 1f;
            }
            StringBuilder current = new StringBuilder();
            float currentWidth = 0f;
            int lastSpace = -1;
            int i = 0;
            while (i < line.length()) {
                int codePoint = line.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                String chunk = line.substring(i, i + charCount);
                float chunkWidth = width(chunk, font, size);
                if (currentWidth + chunkWidth > maxWidth && current.length() > 0) {
                    int breakAt = lastSpace > 0 && isAscii(codePoint) ? lastSpace : -1;
                    if (breakAt > 0) {
                        String head = current.substring(0, breakAt);
                        String tail = current.substring(breakAt + 1);
                        result.add(trim ? head.stripTrailing() : head);
                        current.setLength(0);
                        current.append(tail);
                        currentWidth = width(tail, font, size);
                    } else {
                        result.add(trim ? current.toString().stripTrailing() : current.toString());
                        current.setLength(0);
                        currentWidth = 0f;
                    }
                    lastSpace = -1;
                }
                current.append(chunk);
                currentWidth += chunkWidth;
                if (codePoint == ' ') {
                    lastSpace = current.length() - 1;
                }
                i += charCount;
            }
            result.add(trim ? current.toString().stripTrailing() : current.toString());
            return result;
        }

        private static boolean isAscii(int codePoint) {
            return codePoint < 128;
        }

        /** 字体缺失字形时替换为 ?，避免 showText 抛异常导致整份报表失败。 */
        private String sanitize(String value, PDType0Font font) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(value.length());
            int i = 0;
            while (i < value.length()) {
                int codePoint = value.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                boolean ok;
                if (codePoint == '\t') {
                    sb.append("    ");
                    i += charCount;
                    continue;
                }
                if (codePoint < 32) {
                    i += charCount;
                    continue;
                }
                Boolean cached = glyphCache.get(codePoint);
                if (cached == null) {
                    try {
                        font.encode(new String(Character.toChars(codePoint)));
                        cached = Boolean.TRUE;
                    } catch (Exception e) {
                        cached = Boolean.FALSE;
                    }
                    glyphCache.put(codePoint, cached);
                }
                ok = cached;
                if (ok) {
                    sb.append(value, i, i + charCount);
                } else {
                    sb.append('?');
                }
                i += charCount;
            }
            return sb.toString();
        }
    }
}
