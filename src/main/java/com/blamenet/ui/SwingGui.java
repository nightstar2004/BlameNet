package com.blamenet.ui;

import com.blamenet.Launcher;
import com.blamenet.collect.Privilege;
import com.blamenet.core.CheckModule;
import com.blamenet.core.EngineListener;
import com.blamenet.core.Options;
import com.blamenet.core.ProgressEvent;
import com.blamenet.core.ProgressTracker;
import com.blamenet.core.ReportPhase;
import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.report.ReportPipeline;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.InstallPaths;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图形界面。
 * <p>
 * 严格按设计要求实现底部进度条：
 * <ul>
 *     <li>窗口最下方固定为“进度条区块”，自上而下依次是：当前测试内容 → 实时进度条 → 状态与耗时；</li>
 *     <li>每个小步骤都通过 {@link ProgressTracker} 推送事件，进度条必定推进；</li>
 *     <li>中部为模块结果、模块详情、外部验证清单与运行日志四个页签；</li>
 *     <li>外部验证项支持回填，并可据此重新生成全套报告（仍走 JSON 主数据源同源导出）。</li>
 * </ul>
 */
public final class SwingGui extends JFrame implements EngineListener, ProgressTracker.Listener {

    private static final long serialVersionUID = 1L;

    private final Options options;
    private final Report report = new Report();
    /** 诊断过程中用于即时显示的结果列表；诊断结束后切换为引擎产出的最终列表，避免重复行。 */
    private final List<ModuleResult> liveResults = new ArrayList<>();
    private final List<ManualValidationItem> liveManual = new ArrayList<>();
    private final ModuleTableModel moduleModel = new ModuleTableModel();
    private final ManualTableModel manualModel = new ManualTableModel();
    private final JTable moduleTable = new JTable(moduleModel);
    private final JTable manualTable = new JTable(manualModel);
    private final JTextArea detailArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JLabel currentContentLabel = new JLabel("准备就绪");
    private final JLabel stateLabel = new JLabel("尚未开始");
    private final JLabel privilegeLabel = new JLabel();
    private final JLabel moduleTimeLabel = new JLabel("当前模块已用时长：—　参考时长：—");
    private final JProgressBar progressBar = new JProgressBar(0, 10_000);
    private final JProgressBar reportProgressBar = new JProgressBar(0, ReportPhase.PLANNED_STEPS);

    private final JButton startButton = new JButton("开始诊断");
    private final JButton abortButton = new JButton("中止");
    private final JButton elevateButton = new JButton("以管理员身份重新运行");
    private final JButton openDirButton = new JButton("打开结果目录");
    private final JButton openHtmlButton = new JButton("打开 HTML 报告");
    private final JButton openZipButton = new JButton("打开 ZIP");
    private final JButton regenerateButton = new JButton("重新生成报表");
    private final JButton exportWizardButton = new JButton("导出向导");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);
    private final Timer clockTimer;

    private ProgressTracker activeTracker;
    private ProgressEvent lastEvent;
    private ReportPipeline.Bundle lastBundle;
    private boolean admin;

    /** 当前模块的开始时间（纳秒），用于计算模块已用时长。 */
    private long moduleStartNanos;
    /** 当前模块的参考时长（秒）。 */
    private int currentModuleReferenceSeconds;
    /** 是否已进入报告生成阶段。 */
    private boolean inReportPhase;
    /** 进入报告阶段前的已完成步数（用于计算报告阶段内进度）。 */
    private int reportPhaseBaseSteps;

    private SwingGui(Options options) {
        super(BuildInfo.toolName() + " " + BuildInfo.toolVersion()
                + " —— Windows 网络故障定位工具（只读诊断）");
        this.options = options;
        this.admin = Launcher.isAdmin();

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 720));
        buildUi();
        setLocationRelativeTo(null);

        clockTimer = new Timer(1000, e -> refreshClock());
        clockTimer.start();

        refreshActions();
        if (!admin) {
            SwingUtilities.invokeLater(this::askElevationOnStartup);
        }
    }

    /** 由 {@link Launcher} 反射调用，避免无图形环境时对 Swing 类的硬依赖。 */
    public static void launch(Options options) {
        SwingUtilities.invokeLater(() -> {
            SwingGui gui = new SwingGui(options);
            gui.setVisible(true);
        });
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi() {
        setLayout(new BorderLayout());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(startButton);
        toolBar.add(abortButton);
        toolBar.addSeparator();
        toolBar.add(elevateButton);
        toolBar.addSeparator();
        toolBar.add(regenerateButton);
        toolBar.add(exportWizardButton);
        toolBar.add(openDirButton);
        toolBar.add(openHtmlButton);
        toolBar.add(openZipButton);
        toolBar.add(Box.createHorizontalGlue());
        privilegeLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        toolBar.add(privilegeLabel);
        add(toolBar, BorderLayout.NORTH);

        moduleTable.setAutoCreateRowSorter(true);
        moduleTable.setRowHeight(24);
        moduleTable.getTableHeader().setReorderingAllowed(false);
        configureColumnWidths(moduleTable, new int[]{60, 330, 80, 80, 100, 90, 70, 90});
        moduleTable.setDefaultRenderer(Object.class, new ModuleCellRenderer());
        moduleTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedModule();
            }
        });

        manualTable.setRowHeight(24);
        manualTable.getTableHeader().setReorderingAllowed(false);
        configureColumnWidths(manualTable, new int[]{70, 260, 460, 90, 160});
        manualTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    fillSelectedManualItem();
                }
            }
        });

        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detailArea.setLineWrap(false);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel manualPanel = new JPanel(new BorderLayout());
        manualPanel.add(new JScrollPane(manualTable), BorderLayout.CENTER);
        JPanel manualButtons = new JPanel();
        JButton fillButton = new JButton("回填验证结果");
        JButton fillHint = new JButton("查看任务说明");
        JButton regenerateFromFill = new JButton("回填后重新生成报表");
        manualButtons.add(fillButton);
        manualButtons.add(fillHint);
        manualButtons.add(regenerateFromFill);
        manualPanel.add(manualButtons, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("模块结果", new JScrollPane(moduleTable));
        tabs.addTab("模块详情", new JScrollPane(detailArea));
        tabs.addTab("外部验证清单", manualPanel);
        tabs.addTab("运行日志", new JScrollPane(logArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, progressPanel());
        split.setResizeWeight(0.82);
        split.setDividerLocation(480);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        startButton.addActionListener(e -> startDiagnosis());
        abortButton.addActionListener(e -> confirmAbort());
        elevateButton.addActionListener(e -> elevateAndRestart());
        regenerateButton.addActionListener(e -> regenerateReports());
        exportWizardButton.addActionListener(e -> openExportWizard());
        openDirButton.addActionListener(e -> openPath(resultDirectory()));
        openHtmlButton.addActionListener(e -> openPath(artifactPath("human-readable-report")));
        openZipButton.addActionListener(e -> openPath(artifactPath("bundle-zip")));
        fillButton.addActionListener(e -> fillSelectedManualItem());
        fillHint.addActionListener(e -> showSelectedManualInstructions());
        regenerateFromFill.addActionListener(e -> {
            if (applyFilledResultsToReport()) {
                regenerateReports();
            }
        });
    }

    /** 窗口最下方的进度条区块：当前测试内容在上，依次为测试进度条、模块已用时长、报告生成进度条。 */
    private JPanel progressPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xC8, 0xD0, 0xDA)),
                BorderFactory.createEmptyBorder(8, 12, 10, 12)));

        currentContentLabel.setFont(currentContentLabel.getFont().deriveFont(Font.BOLD, 14f));
        currentContentLabel.setText("当前测试内容：等待开始");
        panel.add(currentContentLabel, BorderLayout.NORTH);

        // 中部：测试进度条 + 模块已用时长 + 报告生成进度条（垂直排列）。
        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));

        progressBar.setStringPainted(true);
        progressBar.setString("0.0%");
        progressBar.setPreferredSize(new Dimension(100, 26));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(progressBar);

        moduleTimeLabel.setFont(moduleTimeLabel.getFont().deriveFont(Font.PLAIN, 12f));
        moduleTimeLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        moduleTimeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(moduleTimeLabel);

        JLabel reportLabel = new JLabel("报告生成进度：");
        reportLabel.setFont(reportLabel.getFont().deriveFont(Font.PLAIN, 12f));
        reportLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(reportLabel);

        reportProgressBar.setStringPainted(true);
        reportProgressBar.setString("0");
        reportProgressBar.setPreferredSize(new Dimension(100, 22));
        reportProgressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        reportProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(reportProgressBar);

        panel.add(center, BorderLayout.CENTER);

        stateLabel.setPreferredSize(new Dimension(430, 22));
        panel.add(stateLabel, BorderLayout.EAST);
        return panel;
    }

    private static void configureColumnWidths(JTable table, int[] widths) {
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);
        }
    }

    // ------------------------------------------------------------------ 诊断流程

    private void startDiagnosis() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        abortRequested.set(false);
        moduleStartNanos = 0;
        currentModuleReferenceSeconds = 0;
        inReportPhase = false;
        reportPhaseBaseSteps = 0;
        reportProgressBar.setValue(0);
        reportProgressBar.setString("0");
        moduleTimeLabel.setText("当前模块已用时长：—　参考时长：—");
        report.moduleResults.clear();
        report.manualValidationList.clear();
        liveResults.clear();
        liveManual.clear();
        moduleModel.setRows(liveResults);
        manualModel.setRows(liveManual);
        detailArea.setText("");
        logArea.setText("");
        refreshActions();
        stateLabel.setText("正在诊断…");

        ProgressTracker tracker = new ProgressTracker();
        tracker.addListener(this);
        activeTracker = tracker;

        Thread worker = new Thread(() -> {
            long start = System.currentTimeMillis();
            ReportPipeline.Bundle bundle;
            try {
                bundle = Launcher.diagnoseAndReport(options, tracker, this, report);
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                SwingUtilities.invokeLater(() -> {
                    running.set(false);
                    activeTracker = null;
                    // 切换到引擎产出的最终结果列表（内容与报告一致，不含重复行）。
                    moduleModel.setRows(report.moduleResults);
                    manualModel.setRows(report.manualValidationList);
                    refreshActions();
                    stateLabel.setText(stateLabel.getText() + "｜总耗时 " + TimeUtil.duration(elapsed));
                });
            }
            SwingUtilities.invokeLater(() -> onBundleReady(bundle));
        }, "blamenet-diagnosis");
        worker.setDaemon(true);
        worker.start();
    }

    /** 中止：先确认，确认后才置位中止标记并提示等待当前模块结束。 */
    private void confirmAbort() {
        if (!running.get()) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "确定中止测试吗？", "中止测试",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        abortRequested.set(true);
        stateLabel.setText("正在中止…");
        appendLog("用户请求中止，将在当前步骤结束后停止。");
        JOptionPane.showMessageDialog(this, "正在中止，请等待当前模块测试完毕。", "正在中止",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void onBundleReady(ReportPipeline.Bundle bundle) {
        if (bundle == null) {
            appendLog("报告生成未完成（详见上方错误信息）。");
            return;
        }
        lastBundle = bundle;
        if (bundle.directory() != null) {
            currentContentLabel.setText("报告已生成：" + bundle.directory().resolve(bundle.reportId()));
            progressBar.setValue(progressBar.getMaximum());
            progressBar.setString("100.0%");
            reportProgressBar.setValue(reportProgressBar.getMaximum());
            reportProgressBar.setString(String.valueOf(reportProgressBar.getMaximum()));
        }
        appendSummaryToLog(bundle);
        if (bundle.hasFailure()) {
            StringBuilder sb = new StringBuilder("以下形态生成失败：");
            for (ReportPipeline.Failure failure : bundle.failures()) {
                sb.append("\n  - ").append(failure.displayName()).append("：").append(failure.message());
            }
            appendLog(sb.toString());
            JOptionPane.showMessageDialog(this, sb.toString(), "部分形态生成失败", JOptionPane.WARNING_MESSAGE);
        }
        refreshActions();
    }

    private void appendSummaryToLog(ReportPipeline.Bundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append("诊断结果：状态（源值）").append(TextUtil.nvl(report.testMeta.status))
                .append("，管理员权限 ").append(report.testMeta.adminPrivilege)
                .append("，模块总 ").append(report.summary.totalModuleCount)
                .append(" / 通过 ").append(report.summary.passCount)
                .append(" / 警告 ").append(report.summary.warnCount)
                .append(" / 失败 ").append(report.summary.failCount)
                .append(" / 手动 ").append(report.summary.manualCount)
                .append(" / 信息 ").append(report.summary.infoCount);
        appendLog(sb.toString());
        appendLog("风险等级：" + RiskLevel.fromSource(report.summary.riskLevel).chinese()
                + "（源值 " + TextUtil.nvl(report.summary.riskLevel) + "）");
        if (TextUtil.isNotBlank(report.summary.globalDiagnosis)) {
            appendLog("全局诊断：" + report.summary.globalDiagnosis);
        }
        if (bundle.directory() != null) {
            appendLog("输出目录：" + bundle.directory());
            for (ReportPipeline.Artifact artifact : bundle.artifacts()) {
                appendLog("  · " + artifact.displayName() + "：" + artifact.path().getFileName()
                        + "（" + TextUtil.humanSize(artifact.sizeBytes()) + "）");
            }
        }
    }

    private void regenerateReports() {
        if (running.get()) {
            JOptionPane.showMessageDialog(this, "诊断正在进行中，请稍后再试。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (report.moduleResults.isEmpty()) {
            JOptionPane.showMessageDialog(this, "还没有可用的诊断结果，请先执行一次诊断。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        running.set(true);
        refreshActions();
        currentContentLabel.setText("当前测试内容：正在根据 JSON 主数据源重新生成全套报告");
        stateLabel.setText("正在重新生成报表…");
        moduleStartNanos = 0;
        inReportPhase = false;
        reportPhaseBaseSteps = 0;
        reportProgressBar.setValue(0);
        reportProgressBar.setString("0");

        ProgressTracker tracker = new ProgressTracker();
        tracker.addListener(this);
        tracker.plan(List.of(ProgressTracker.PlanItem.phase(ReportPhase.ID, ReportPhase.NAME,
                ReportPhase.PLANNED_STEPS)));
        activeTracker = tracker;

        Thread worker = new Thread(() -> {
            ReportPipeline.Bundle bundle;
            try {
                bundle = ReportPipeline.run(report, options, tracker, this);
            } catch (Exception e) {
                appendLog("重新生成报表失败：" + e.getMessage());
                bundle = null;
            } finally {
                tracker.finish();
                SwingUtilities.invokeLater(() -> {
                    running.set(false);
                    activeTracker = null;
                    refreshActions();
                    stateLabel.setText("重新生成完成");
                });
            }
            ReportPipeline.Bundle ready = bundle;
            SwingUtilities.invokeLater(() -> onBundleReady(ready));
        }, "blamenet-report");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ 导出向导

    /** 导出向导：先勾选格式，再选择导出目录（默认安装目录\reports），导出到 BlameNet-<编号> 子文件夹。 */
    private void openExportWizard() {
        if (running.get()) {
            JOptionPane.showMessageDialog(this, "诊断正在进行中，请稍后再试。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (report.moduleResults.isEmpty()) {
            JOptionPane.showMessageDialog(this, "还没有可用的诊断结果，请先执行一次诊断。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 第一步：格式勾选对话框。
        JCheckBox jsonBox = new JCheckBox("JSON 主数据源", true);
        jsonBox.setEnabled(false); // JSON 是唯一主数据源，始终导出
        JCheckBox htmlBox = new JCheckBox("HTML 报告", true);
        JCheckBox mdBox = new JCheckBox("Markdown 报告（MD）", true);
        JCheckBox txtBox = new JCheckBox("纯文本报告（TXT）", true);
        JCheckBox pdfConciseBox = new JCheckBox("PDF 报表（简洁版）", true);
        JCheckBox pdfFullBox = new JCheckBox("PDF 报表（完整版）", false);
        JCheckBox xlsxBox = new JCheckBox("表格报告（XLSX）", true);
        JCheckBox zipBox = new JCheckBox("打包 ZIP", true);

        JPanel formatPanel = new JPanel();
        formatPanel.setLayout(new javax.swing.BoxLayout(formatPanel, javax.swing.BoxLayout.Y_AXIS));
        formatPanel.add(new JLabel("请选择要导出的格式（JSON 为主数据源，始终导出）："));
        formatPanel.add(Box.createVerticalStrut(6));
        formatPanel.add(jsonBox);
        formatPanel.add(htmlBox);
        formatPanel.add(mdBox);
        formatPanel.add(txtBox);
        formatPanel.add(pdfConciseBox);
        formatPanel.add(pdfFullBox);
        formatPanel.add(xlsxBox);
        formatPanel.add(zipBox);

        int formatChoice = JOptionPane.showConfirmDialog(this, formatPanel, "导出向导（1/2）——选择格式",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (formatChoice != JOptionPane.OK_OPTION) {
            return;
        }

        Set<String> formats = new LinkedHashSet<>();
        if (htmlBox.isSelected()) {
            formats.add("html");
        }
        if (mdBox.isSelected()) {
            formats.add("md");
        }
        if (txtBox.isSelected()) {
            formats.add("txt");
        }
        if (pdfConciseBox.isSelected()) {
            formats.add("pdf");
        }
        if (pdfFullBox.isSelected()) {
            formats.add("pdf-full");
        }
        if (xlsxBox.isSelected()) {
            formats.add("xlsx");
        }
        boolean includeZip = zipBox.isSelected();

        // 第二步：选择导出目录（Windows 原生资源管理器对话框）。
        String chosen = NativeFolderDialog.pickFolder("选择导出目录（报告将导出到该目录下的 BlameNet-时间戳报告导出 文件夹）");
        if (chosen == null || chosen.isBlank()) {
            return;
        }
        Path targetDirectory = java.nio.file.Path.of(chosen).toAbsolutePath().normalize();

        runExportWizard(formats, includeZip, targetDirectory);
    }

    private void runExportWizard(Set<String> formats, boolean includeZip, Path targetDirectory) {
        running.set(true);
        refreshActions();
        currentContentLabel.setText("当前测试内容：正在按所选格式导出报告");
        stateLabel.setText("正在导出…");
        moduleStartNanos = 0;
        inReportPhase = false;
        reportPhaseBaseSteps = 0;
        reportProgressBar.setValue(0);
        reportProgressBar.setString("0");

        ProgressTracker tracker = new ProgressTracker();
        tracker.addListener(this);
        tracker.plan(List.of(ProgressTracker.PlanItem.phase(ReportPhase.ID, ReportPhase.NAME,
                ReportPhase.PLANNED_STEPS)));
        activeTracker = tracker;

        Thread worker = new Thread(() -> {
            ReportPipeline.Bundle bundle = null;
            try {
                bundle = ReportPipeline.exportSelected(report, formats, includeZip, targetDirectory, tracker, this);
            } catch (Exception e) {
                appendLog("导出向导失败：" + e.getMessage());
            } finally {
                tracker.finish();
                ReportPipeline.Bundle finished = bundle;
                SwingUtilities.invokeLater(() -> {
                    running.set(false);
                    activeTracker = null;
                    refreshActions();
                    stateLabel.setText(finished == null ? "导出失败" : "导出完成");
                });
            }
            ReportPipeline.Bundle ready = bundle;
            SwingUtilities.invokeLater(() -> {
                if (ready == null) {
                    JOptionPane.showMessageDialog(this, "导出失败，请查看运行日志。", "导出失败",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                onBundleReady(ready);
                JOptionPane.showMessageDialog(this,
                        "已导出到：\n" + ready.directory(), "导出完成", JOptionPane.INFORMATION_MESSAGE);
            });
        }, "blamenet-export-wizard");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ 提权

    private void askElevationOnStartup() {
        int choice = JOptionPane.showOptionDialog(this,
                "当前不是以管理员身份运行。\n\n"
                        + "只读诊断仍可继续，但以下检查无法执行或只能给出“需要管理员权限”的结论：\n"
                        + "  · 模块 25 系统文件完整性（sfc / DISM 只读检查）\n"
                        + "  · 模块 26 数据包捕获与丢弃统计（pktmon）\n\n"
                        + "是否以管理员身份重新运行？",
                "建议提权（唯一的系统级主动动作）",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new Object[]{"以管理员身份重新运行", "继续只读诊断"}, "继续只读诊断");
        if (choice == 0) {
            elevateAndRestart();
        }
    }

    private void elevateAndRestart() {
        if (Privilege.check().admin()) {
            JOptionPane.showMessageDialog(this, "当前已经是管理员权限，无需提权。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            admin = true;
            refreshActions();
            return;
        }
        boolean relaunched = Privilege.relaunchElevated("--gui");
        if (relaunched) {
            appendLog("已请求以管理员身份重新运行，本窗口将关闭。");
            dispose();
            System.exit(0);
        } else {
            JOptionPane.showMessageDialog(this,
                    "提权被取消或失败（UAC 未确认）。可继续以普通权限执行只读诊断。",
                    "提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ 回填

    private ManualValidationItem selectedManualItem() {
        int row = manualTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = manualTable.convertRowIndexToModel(row);
        return manualRow(modelRow);
    }

    private ManualValidationItem manualRow(int modelRow) {
        List<ManualValidationItem> rows = manualModel.rows();
        return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
    }

    private void showSelectedManualInstructions() {
        ManualValidationItem item = selectedManualItem();
        if (item == null) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择一条任务。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTextArea area = new JTextArea(TextUtil.nvl(item.instructions), 8, 52);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                "模块 " + TextUtil.nvl(item.moduleId) + " " + TextUtil.nvl(item.taskTitle),
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void fillSelectedManualItem() {
        ManualValidationItem item = selectedManualItem();
        if (item == null) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择一条任务。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTextArea area = new JTextArea(TextUtil.nvl(item.userFillResult), 6, 52);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel label = new JLabel("<html><b>模块 " + TextUtil.nvl(item.moduleId) + "　"
                + TextUtil.nvl(item.taskTitle) + "</b><br>" + escapeHtml(TextUtil.nvl(item.instructions))
                + "</html>");
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        JLabel hint = new JLabel("填写实际验证结果，例如：PE 环境下网络正常 / 异常。留空表示仍未验证。");
        panel.add(hint, BorderLayout.SOUTH);

        int result = JOptionPane.showConfirmDialog(this, panel, "回填外部验证结果",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        String text = area.getText().trim();
        item.userFillResult = text.isEmpty() ? null : text;
        item.filledAt = text.isEmpty() ? null : TimeUtil.isoNow();
        manualModel.fireTableDataChanged();
        appendLog("已回填模块 " + TextUtil.nvl(item.moduleId) + " 的验证结果："
                + (text.isEmpty() ? "（已清空）" : text));
    }

    /** 把回填结果同步到对应的模块结果上，使报告中的 ManualVerified / ManualNote 与之一致。 */
    private boolean applyFilledResultsToReport() {
        int count = 0;
        for (ManualValidationItem item : report.manualValidationList) {
            ModuleResult module = report.findModule(item.moduleId);
            if (module == null) {
                continue;
            }
            module.manualVerified = item.isFilled();
            module.manualNote = item.isFilled() ? item.userFillResult : null;
            count++;
        }
        if (count == 0) {
            JOptionPane.showMessageDialog(this, "没有可同步的回填结果。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        appendLog("已把 " + count + " 条回填结果同步到模块结果，随后重新生成报表。");
        return true;
    }

    private void showSelectedModule() {
        int row = moduleTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = moduleTable.convertRowIndexToModel(row);
        List<ModuleResult> rows = moduleModel.rows();
        if (modelRow < 0 || modelRow >= rows.size()) {
            return;
        }
        ModuleResult module = rows.get(modelRow);
        StringBuilder sb = new StringBuilder();
        sb.append("模块 ").append(TextUtil.nvl(module.moduleId)).append("　")
                .append(TextUtil.nvl(module.moduleName)).append('\n');
        sb.append("状态：").append(module.statusEnum().chinese())
                .append("（源值 ").append(TextUtil.nvl(module.status)).append("）\n");
        sb.append("分类：").append(TextUtil.nvl(module.category))
                .append("　耗时：").append(TextUtil.nvl(module.duration))
                .append("　命令条数：").append(module.executedCommandCount)
                .append("　需要管理员：").append(module.requiresAdmin).append('\n');
        sb.append('\n').append("【结论摘要】").append('\n').append(TextUtil.nvl(module.summary)).append('\n');
        sb.append('\n').append("【根因推断】").append('\n').append(TextUtil.nvl(module.diagnosis)).append('\n');
        sb.append('\n').append("【修复建议（需手动执行，工具不会自动修改配置）】").append('\n')
                .append(TextUtil.nvl(module.suggestion)).append('\n');
        if (module.manualNote != null || module.manualVerified != null) {
            sb.append('\n').append("【人工回填】").append(Boolean.TRUE.equals(module.manualVerified) ? "已验证" : "未验证");
            if (TextUtil.isNotBlank(module.manualNote)) {
                sb.append("：").append(module.manualNote);
            }
            sb.append('\n');
        }
        if (module.manualTask != null) {
            sb.append('\n').append("【外部验证任务】").append(TextUtil.nvl(module.manualTask.taskTitle)).append('\n')
                    .append(TextUtil.nvl(module.manualTask.instructions)).append('\n');
        }
        List<Evidence> evidences = module.evidences == null ? List.of() : module.evidences;
        sb.append('\n').append("【证据链（").append(evidences.size()).append(" 条）】").append('\n');
        int index = 1;
        for (Evidence evidence : evidences) {
            sb.append('\n').append("证据 ").append(index++).append("：")
                    .append(TextUtil.nvl(evidence.evidenceName)).append('\n');
            sb.append("  命令：").append(TextUtil.nvl(evidence.rawCommand)).append('\n');
            sb.append("  记录时间：").append(TextUtil.nvl(evidence.recordTime))
                    .append("　退出码：").append(evidence.exitCode == null ? "—" : evidence.exitCode)
                    .append("　耗时：").append(evidence.durationMillis == null ? "—" : evidence.durationMillis + " ms")
                    .append("　超时：").append(evidence.timedOut == null ? "—" : evidence.timedOut).append('\n');
            sb.append("  输出：\n").append(indent(TextUtil.nvl(evidence.rawOutput))).append('\n');
        }
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 进度与事件

    @Override
    public void onProgress(ProgressEvent event) {
        SwingUtilities.invokeLater(() -> {
            lastEvent = event;
            currentContentLabel.setText("当前测试内容：" + event.currentContent());
            progressBar.setValue((int) Math.round(event.percent() * 100));
            progressBar.setString(String.format(java.util.Locale.ROOT, "%.1f%%  （%d/%d 步）",
                    event.percent(), event.completedSteps(), event.totalSteps()));
            stateLabel.setText(stateText(event));
            updateReportProgress(event);
        });
    }

    /** 报告生成进度条：仅在报告阶段推进，测试阶段保持 0。 */
    private void updateReportProgress(ProgressEvent event) {
        boolean reportPhase = ReportPhase.ID.equals(event.moduleId());
        if (reportPhase) {
            if (!inReportPhase) {
                inReportPhase = true;
                // 进入报告阶段的第一个事件已完成报告阶段第 1 步。
                reportPhaseBaseSteps = event.completedSteps() - 1;
            }
            int done = Math.max(0, event.completedSteps() - reportPhaseBaseSteps);
            int max = reportProgressBar.getMaximum();
            reportProgressBar.setValue(Math.min(done, max));
            reportProgressBar.setString(done + "/" + max);
        } else {
            inReportPhase = false;
            reportProgressBar.setValue(0);
            reportProgressBar.setString("0");
        }
    }

    private String stateText(ProgressEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("已用 ").append(TimeUtil.duration(event.elapsedMillis()));
        if (event.remainingMillis() > 0 && !event.finished()) {
            sb.append("　预计剩余 ").append(TimeUtil.duration(event.remainingMillis()));
        }
        if (event.moduleId() != null && !event.moduleId().isBlank() && !ReportPhase.ID.equals(event.moduleId())) {
            sb.append("　当前模块 ").append(event.moduleId());
        }
        return sb.toString();
    }

    private void refreshClock() {
        ProgressTracker tracker = activeTracker;
        if (tracker == null || lastEvent == null || !running.get()) {
            return;
        }
        long elapsed = tracker.elapsedMillis();
        stateLabel.setText("已用 " + TimeUtil.duration(elapsed)
                + "　进度 " + lastEvent.completedSteps() + "/" + lastEvent.totalSteps() + " 步");
        updateModuleTimeLabel();
    }

    /** 刷新当前模块已用时长与参考时长；超过 5 秒追加耐心等待提示。 */
    private void updateModuleTimeLabel() {
        if (moduleStartNanos == 0 || inReportPhase) {
            return;
        }
        long moduleElapsed = (System.nanoTime() - moduleStartNanos) / 1_000_000L;
        String text = "当前模块已用时长：" + TimeUtil.duration(moduleElapsed)
                + "　参考时长：" + currentModuleReferenceSeconds + " 秒";
        if (moduleElapsed > 5000) {
            text += "　—— 本子模块测试条目较多，为确保数据完整性，请耐心等待";
        }
        moduleTimeLabel.setText(text);
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(TimeUtil.human(java.time.Instant.now()) + "  " + message + "\n");
            int max = 6000;
            if (logArea.getLineCount() > max) {
                try {
                    logArea.replaceRange("", 0, logArea.getLineEndOffset(0));
                } catch (Exception ignored) {
                    // 极端情况下忽略裁剪失败，保证日志不丢。
                }
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    @Override
    public void onModuleStarted(CheckModule module, int index, int total) {
        SwingUtilities.invokeLater(() -> {
            moduleStartNanos = System.nanoTime();
            currentModuleReferenceSeconds = module.referenceSeconds();
            moduleTimeLabel.setText("当前模块已用时长：0.000 秒　参考时长："
                    + currentModuleReferenceSeconds + " 秒");
            stateLabel.setText(String.format("模块 %d/%d：%s %s", index, total, module.id(), module.name()));
        });
    }

    @Override
    public void onModuleFinished(ModuleResult result) {
        SwingUtilities.invokeLater(() -> {
            liveResults.add(result);
            moduleModel.setRows(liveResults);
        });
    }

    @Override
    public void onStatus(String status) {
        SwingUtilities.invokeLater(() -> stateLabel.setText(status));
    }

    @Override
    public boolean abortRequested() {
        return abortRequested.get();
    }

    // ------------------------------------------------------------------ 结果与文件

    private Path resultDirectory() {
        if (lastBundle != null && lastBundle.directory() != null) {
            return lastBundle.directory();
        }
        try {
            return options.outputDir != null ? options.outputDir : InstallPaths.resultsDir();
        } catch (IOException e) {
            return null;
        }
    }

    private Path artifactPath(String role) {
        return lastBundle == null ? null : lastBundle.pathOf(role);
    }

    private void openPath(Path path) {
        if (path == null || !Files.exists(path)) {
            JOptionPane.showMessageDialog(this, "目标还不存在，请先执行诊断生成报告。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                JOptionPane.showMessageDialog(this, "当前环境不支持直接打开文件，路径：\n" + path, "提示",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "打开失败：" + e.getMessage() + "\n路径：" + path, "提示",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void refreshActions() {
        boolean busy = running.get();
        startButton.setEnabled(!busy);
        abortButton.setEnabled(busy);
        regenerateButton.setEnabled(!busy && !report.moduleResults.isEmpty());
        exportWizardButton.setEnabled(!busy && !report.moduleResults.isEmpty());
        openDirButton.setEnabled(!busy || lastBundle != null);
        openHtmlButton.setEnabled(artifactPath("human-readable-report") != null);
        openZipButton.setEnabled(artifactPath("bundle-zip") != null);
        elevateButton.setEnabled(!admin);
        privilegeLabel.setText(admin ? "权限：管理员" : "权限：普通用户（部分只读检查不可执行）");
        privilegeLabel.setForeground(admin ? new Color(0x15, 0x73, 0x47) : new Color(0xB3, 0x5C, 0x00));
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ 表格模型

    /** 模块结果表模型。 */
    private static final class ModuleTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {
                "编号", "模块名称", "状态", "源值", "分类", "耗时", "证据", "手动回填"
        };

        private List<ModuleResult> rows = new ArrayList<>();

        void setRows(List<ModuleResult> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<ModuleResult> rows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ModuleResult module = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> TextUtil.nvl(module.moduleId);
                case 1 -> TextUtil.nvl(module.moduleName);
                case 2 -> module.statusEnum().chinese();
                case 3 -> TextUtil.nvl(module.status);
                case 4 -> TextUtil.nvl(module.category);
                case 5 -> TextUtil.nvl(module.duration);
                case 6 -> module.evidences == null ? 0 : module.evidences.size();
                default -> module.manualTask == null ? "—"
                        : (Boolean.TRUE.equals(module.manualVerified) ? "已回填" : "待回填");
            };
        }
    }

    /** 外部验证清单表模型。 */
    private static final class ManualTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"模块", "任务标题", "操作说明", "优先级", "用户填写结果"};

        private List<ManualValidationItem> rows = new ArrayList<>();

        void setRows(List<ManualValidationItem> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<ManualValidationItem> rows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ManualValidationItem item = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> TextUtil.nvl(item.moduleId);
                case 1 -> TextUtil.nvl(item.taskTitle);
                case 2 -> TextUtil.nvl(item.instructions);
                case 3 -> TextUtil.nvl(item.priority);
                default -> item.isFilled() ? item.userFillResult : "待填写";
            };
        }
    }

    /** 表格单元渲染：状态列着色，失败/警告行整体加醒目颜色。 */
    private static final class ModuleCellRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;
        private static final Color PASS = new Color(0x15, 0x73, 0x47);
        private static final Color WARN = new Color(0xA9, 0x5A, 0x06);
        private static final Color FAIL = new Color(0xB3, 0x1C, 0x1C);
        private static final Color MANUAL = new Color(0x5B, 0x2A, 0x9B);
        private static final Color INFO = new Color(0x44, 0x55, 0x66);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (isSelected) {
                return component;
            }
            int modelRow = table.convertRowIndexToModel(row);
            if (table.getModel() instanceof ModuleTableModel model) {
                List<ModuleResult> rows = model.rows();
                if (modelRow >= 0 && modelRow < rows.size()) {
                    Status status = rows.get(modelRow).statusEnum();
                    Color color = switch (status) {
                        case PASS -> PASS;
                        case WARN -> WARN;
                        case FAIL -> FAIL;
                        case MANUAL -> MANUAL;
                        case INFO -> INFO;
                    };
                    if (column == 2) {
                        component.setForeground(color);
                    } else if (status == Status.FAIL) {
                        component.setForeground(FAIL);
                    } else if (status == Status.WARN) {
                        component.setForeground(WARN);
                    } else {
                        component.setForeground(Color.DARK_GRAY);
                    }
                }
            }
            return component;
        }
    }

    @Override
    public void dispose() {
        clockTimer.stop();
        super.dispose();
    }
}
