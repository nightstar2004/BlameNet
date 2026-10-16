package com.blamenet.ui;

import com.blamenet.Launcher;
import com.blamenet.core.CheckModule;
import com.blamenet.core.EngineListener;
import com.blamenet.core.Options;
import com.blamenet.core.ProgressEvent;
import com.blamenet.core.ProgressTracker;
import com.blamenet.core.ReportPhase;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.Status;
import com.blamenet.model.TestStatus;
import com.blamenet.report.ReportPipeline;
import com.blamenet.util.InstallPaths;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令行界面。
 * <p>
 * 严格按设计要求实现“界面最下方实时进度条、进度条上方显示当前测试内容”：
 * <pre>
 * 当前测试内容 : 模块 12 检查网关连通性 —— 正在 ping 网关 192.168.1.1（100 次）
 * [■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■□□□□□□□□] 82.6%  已完成 106/128 步  已用 00:02:11  预计剩余 00:00:27
 * </pre>
 * 支持 ANSI 的终端使用两行原地刷新；不支持时退化为单行回车刷新，日志始终不会被进度条覆盖。
 */
public final class CliRunner implements EngineListener, ProgressTracker.Listener {

    private static final int BAR_WIDTH = 44;

    private final Options options;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean ansi;
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private ProgressEvent lastEvent;
    private boolean progressBlockDrawn;
    private int moduleIndex;
    private int moduleTotal;

    public CliRunner(Options options) {
        this.options = options;
        this.out = System.out;
        this.err = System.err;
        this.ansi = detectAnsi();
    }

    // ------------------------------------------------------------------ 入口

    /** 执行完整诊断 + 报告生成。 */
    public void run() {
        installAbortHook();
        printBanner();
        ProgressTracker tracker = new ProgressTracker();
        tracker.addListener(this);

        long start = System.currentTimeMillis();
        Report report = new Report();
        ReportPipeline.Bundle bundle = Launcher.diagnoseAndReport(options, tracker, this, report);
        long elapsed = System.currentTimeMillis() - start;

        completed.set(true);
        clearProgressBlock();
        printSummary(report, bundle, elapsed);
    }

    /** 仅从已有 JSON 主数据源重建全部报表。 */
    public void runRebuild() {
        printBanner();
        ProgressTracker tracker = new ProgressTracker();
        tracker.addListener(this);
        tracker.plan(List.of(ProgressTracker.PlanItem.phase(ReportPhase.ID, ReportPhase.NAME,
                ReportPhase.PLANNED_STEPS)));
        ReportPipeline.Bundle bundle;
        try {
            bundle = ReportPipeline.rebuild(options.rebuildJson, options, tracker, this);
        } catch (Exception e) {
            tracker.finish();
            clearProgressBlock();
            err.println("重建报表失败：" + e.getMessage());
            return;
        }
        tracker.finish();
        completed.set(true);
        clearProgressBlock();
        out.println();
        out.println("已从 JSON 主数据源重建全部报表：");
        out.println("  报告编号 : " + bundle.reportId());
        out.println("  输出目录 : " + bundle.directory());
        printArtifacts(bundle);
    }

    // ------------------------------------------------------------------ 界面输出

    private void printBanner() {
        out.println("=".repeat(92));
        out.println("BlameNet —— Windows 网络故障定位工具（只读诊断 · 严格按步骤 · 不跳过 · 不重装系统）");
        out.println("=".repeat(92));
        out.println("结果目录 : " + safeResultsDir());
        out.println("运行档位 : " + options.profileName()
                + (options.quick ? "（快速档：缩减 ping 次数并跳过 sfc / DISM 深度扫描）" : "（完整档）"));
        out.println("模块范围 : " + options.moduleFilterText());
        out.println("界面模式 : 命令行");
        out.println("-".repeat(92));
    }

    private String safeResultsDir() {
        try {
            return options.outputDir != null ? options.outputDir.toString() : InstallPaths.resultsDir().toString();
        } catch (Exception e) {
            return "(不可用)";
        }
    }

    private void printSummary(Report report, ReportPipeline.Bundle bundle, long elapsedMillis) {
        out.println();
        out.println("-".repeat(92));
        out.println("诊断结果");
        out.println("-".repeat(92));
        out.println("测试状态（源值） : " + TextUtil.nvl(report.testMeta.status)
                + "（" + chineseTestStatus(report.testMeta.status) + "）");
        out.println("管理员权限       : " + report.testMeta.adminPrivilege);
        out.println("总耗时           : " + TimeUtil.duration(elapsedMillis));
        out.println("模块统计         : 总 " + report.summary.totalModuleCount
                + " / 通过 " + report.summary.passCount
                + " / 警告 " + report.summary.warnCount
                + " / 失败 " + report.summary.failCount
                + " / 手动 " + report.summary.manualCount
                + " / 信息 " + report.summary.infoCount);
        out.println("风险等级         : " + riskChinese(report.summary.riskLevel)
                + "（源值 " + TextUtil.nvl(report.summary.riskLevel) + "）");
        if (!report.summary.faultLayers.isEmpty()) {
            out.println("故障分层         : " + String.join("；", report.summary.faultLayers));
        }
        out.println();
        out.println("全局诊断：");
        out.println("  " + TextUtil.nvl(report.summary.globalDiagnosis));

        if (!report.summary.failingModules.isEmpty()) {
            out.println();
            out.println("失败模块：" + String.join("；", report.summary.failingModules));
        }
        if (!report.summary.warningModules.isEmpty()) {
            out.println("警告模块：" + String.join("；", report.summary.warningModules));
        }
        if (!report.manualValidationList.isEmpty()) {
            out.println();
            out.println("外部验证清单（需人工完成，工具不跳过这些步骤）：");
            int index = 1;
            for (var item : report.manualValidationList) {
                out.println("  " + index++ + ". [模块 " + TextUtil.nvl(item.moduleId) + "] "
                        + TextUtil.nvl(item.taskTitle));
                out.println("     说明：" + TextUtil.nvl(item.instructions));
                out.println("     结果：" + (item.isFilled() ? item.userFillResult : "待填写"));
            }
        }
        if (bundle.directory() != null) {
            out.println();
            out.println("报告输出目录 : " + bundle.directory());
            printArtifacts(bundle);
            if (bundle.hasFailure()) {
                out.println();
                out.println("未生成的形态（已如实登记到 manifest.json 的 failedExports）：");
                for (ReportPipeline.Failure failure : bundle.failures()) {
                    out.println("  - " + failure.displayName() + "：" + failure.message());
                }
            }
        }
        out.println();
        out.println("提示：本工具只做只读诊断，未修改任何系统配置；修复动作请按报告建议手动执行。");
    }

    private void printArtifacts(ReportPipeline.Bundle bundle) {
        for (ReportPipeline.Artifact artifact : bundle.artifacts()) {
            out.println("  - " + padRight(artifact.displayName(), 22) + " "
                    + artifact.path().getFileName() + "（" + TextUtil.humanSize(artifact.sizeBytes()) + "）");
        }
        if (bundle.zipFile() != null) {
            out.println("可直接分享的打包文件：" + bundle.zipFile());
        }
    }

    // ------------------------------------------------------------------ 进度条

    @Override
    public void onProgress(ProgressEvent event) {
        synchronized (this) {
            lastEvent = event;
            redraw(false);
        }
    }

    @Override
    public void onStatus(String status) {
        if (!lastEventDrawn()) {
            return;
        }
        // 状态变化时以“当前测试内容”的方式呈现，进度条位置保持不变。
        synchronized (this) {
            redraw(false, status);
        }
    }

    private boolean lastEventDrawn() {
        return lastEvent != null;
    }

    private void redraw(boolean force) {
        redraw(force, null);
    }

    private void redraw(boolean force, String overrideContent) {
        if (lastEvent == null) {
            return;
        }
        ProgressEvent event = lastEvent;
        String content = overrideContent != null ? overrideContent : event.currentContent();
        String bar = renderBar(event);

        if (!ansi) {
            out.print("\r" + truncateLine(content));
            out.flush();
            return;
        }
        if (progressBlockDrawn) {
            out.print("\r\u001b[2K\u001b[1A\r\u001b[2K");
        }
        out.print("当前测试内容 : " + truncateLine(content) + "\n");
        out.print(bar + (event.finished() ? "\n" : ""));
        out.flush();
        progressBlockDrawn = !event.finished();
        if (event.finished()) {
            progressBlockDrawn = false;
        }
    }

    private String renderBar(ProgressEvent event) {
        int filled = (int) Math.round(BAR_WIDTH * event.percent() / 100.0);
        filled = Math.max(0, Math.min(BAR_WIDTH, filled));
        StringBuilder bar = new StringBuilder();
        bar.append('[');
        bar.append("■".repeat(filled));
        bar.append("□".repeat(BAR_WIDTH - filled));
        bar.append("] ");
        bar.append(String.format(Locale.ROOT, "%5.1f%%", event.percent()));
        bar.append("  已完成 ").append(event.completedSteps()).append('/').append(event.totalSteps()).append(" 步");
        bar.append("  已用 ").append(TimeUtil.duration(event.elapsedMillis()));
        if (event.remainingMillis() > 0) {
            bar.append("  预计剩余 ").append(TimeUtil.duration(event.remainingMillis()));
        }
        if (event.phaseName() != null && !event.phaseName().isBlank()) {
            bar.append("  [").append(shortPhase(event.phaseName())).append(']');
        }
        return bar.toString();
    }

    private static String shortPhase(String phaseName) {
        return phaseName.length() > 18 ? phaseName.substring(0, 18) : phaseName;
    }

    private static String truncateLine(String text) {
        String safe = TextUtil.nvl(text).replace('\n', ' ').replace('\r', ' ');
        int max = 110;
        return safe.length() <= max ? safe : safe.substring(0, max - 1) + "…";
    }

    /** 清除进度条区块，为普通日志腾出干净的输出位置。 */
    private void clearProgressBlock() {
        if (!ansi || !progressBlockDrawn) {
            return;
        }
        synchronized (this) {
            out.print("\r\u001b[2K\u001b[1A\r\u001b[2K");
            out.flush();
            progressBlockDrawn = false;
        }
    }

    // ------------------------------------------------------------------ 事件

    @Override
    public void onLog(String message) {
        clearProgressBlock();
        synchronized (this) {
            out.println(message);
        }
    }

    @Override
    public void onModuleStarted(CheckModule module, int index, int total) {
        moduleIndex = index;
        moduleTotal = total;
        clearProgressBlock();
        synchronized (this) {
            out.println();
            out.println(String.format("[%02d/%02d] 模块 %s %s（%s，预计 %d 个步骤）",
                    index, total, module.id(), module.name(), module.layer().chinese(), module.expectedSteps()));
        }
    }

    @Override
    public void onModuleFinished(ModuleResult result) {
        Status status = result.statusEnum();
        clearProgressBlock();
        synchronized (this) {
            out.println(String.format("        → %s（源值 %s）｜耗时 %s｜证据 %d 条",
                    status.chinese(), TextUtil.nvl(result.status), TextUtil.nvl(result.duration),
                    result.evidences == null ? 0 : result.evidences.size()));
            out.println("          " + TextUtil.nvl(result.summary));
            if (status.isProblem() && TextUtil.isNotBlank(result.suggestion)) {
                out.println("          建议：" + TextUtil.nvl(result.suggestion));
            }
        }
    }

    @Override
    public boolean abortRequested() {
        return abortRequested.get();
    }

    private void installAbortHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (completed.get() || abortRequested.getAndSet(true)) {
                return;
            }
            err.println();
            err.println("收到中止信号，正在安全结束（已完成的模块结果会写入报告）…");
        }, "blamenet-abort"));
    }

    /**
     * 是否使用 ANSI 原地刷新。
     * <p>
     * 仅在真正的交互式控制台使用；输出被重定向（管道、日志文件）时退化为单行回车刷新，
     * 避免把光标控制序列写进日志文件。
     */
    private static boolean detectAnsi() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return System.console() != null;
    }

    private static String padRight(String text, int width) {
        String safe = TextUtil.nvl(text);
        int length = safe.length();
        if (length >= width) {
            return safe;
        }
        return safe + " ".repeat(width - length);
    }

    private static String chineseTestStatus(String sourceValue) {
        for (TestStatus status : TestStatus.values()) {
            if (status.sourceValue().equalsIgnoreCase(TextUtil.nvl(sourceValue).trim())) {
                return status.chinese();
            }
        }
        return TextUtil.nvl(sourceValue);
    }

    private static String riskChinese(String sourceValue) {
        return com.blamenet.model.RiskLevel.fromSource(sourceValue).chinese();
    }
}
