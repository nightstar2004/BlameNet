package com.blamenet.core;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Native;
import com.blamenet.collect.PowerShell;
import com.blamenet.collect.Privilege;
import com.blamenet.model.Evidence;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.Status;
import com.blamenet.model.TestEnvironment;
import com.blamenet.model.TestMeta;
import com.blamenet.model.TestStatus;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.InstallPaths;
import com.blamenet.util.JsonUtil;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断调度引擎。
 * <p>
 * 执行流程（对应设计文档“三、诊断调度与报告”的执行流程）：
 * <ol>
 *     <li>预检：权限、环境、工具可用性；</li>
 *     <li>按模块 01 → 31 顺序执行，每个模块独立捕获异常，不因单个失败中断；</li>
 *     <li>每个模块输出结果并计入进度，界面进度条随每个小步骤推进；</li>
 *     <li>由规则引擎生成总判定、风险等级与全局诊断。</li>
 * </ol>
 */
public final class DiagnosticEngine {

    /** 预检阶段计划项编号。 */
    public static final String PREFLIGHT_ID = "00";

    private final List<CheckModule> modules;
    private final EngineListener listener;

    public DiagnosticEngine(List<CheckModule> modules, EngineListener listener) {
        this.modules = modules;
        this.listener = listener;
    }

    /** 构建完整执行计划（预检 + 各模块 + 报告导出），供进度跟踪器使用。 */
    public static List<ProgressTracker.PlanItem> buildPlan(List<CheckModule> modules) {
        List<ProgressTracker.PlanItem> plan = new ArrayList<>();
        plan.add(ProgressTracker.PlanItem.phase(PREFLIGHT_ID, "环境预检与调度初始化", PREFLIGHT_STEPS));
        for (CheckModule module : modules) {
            plan.add(ProgressTracker.PlanItem.module(module.id(), module.name(), module.expectedSteps()));
        }
        plan.add(ProgressTracker.PlanItem.phase(ReportPhase.ID, ReportPhase.NAME, ReportPhase.PLANNED_STEPS));
        return plan;
    }

    private static final int PREFLIGHT_STEPS = 4;

    /** 执行完整诊断，返回报告（尚未分配报告编号，由报告流水线负责）。 */
    public Report run(Options options, ProgressTracker tracker) {
        Instant testStart = Instant.now();
        Report report = new Report();

        tracker.beginNext();
        listener.onStatus("正在进行环境预检…");
        listener.onLog("=== BlameNet 只读诊断开始 ===");
        listener.onLog("安装目录：" + InstallPaths.installDir());
        tracker.step("检查当前进程是否具备管理员权限");
        Privilege.AdminCheck adminCheck = Privilege.check();
        listener.onLog("管理员权限：" + (adminCheck.admin() ? "是" : "否"));

        DiagnosticContext context = new DiagnosticContext(options, tracker, adminCheck.admin(), adminCheck.evidence());

        tracker.step("采集主机与操作系统环境信息");
        TestEnvironment environment = collectEnvironment(context, adminCheck);

        tracker.step("探测关键系统工具可用性");
        environment.toolAvailability = collectTools(context);
        environment.collectorShell = collectShellInfo(context);

        tracker.step("构建模块执行计划并初始化调度引擎");
        listener.onLog("本次计划执行 " + modules.size() + " 个检查模块" + (adminCheck.admin() ? "" : "（未提权）"));

        List<ModuleResult> results = new ArrayList<>();
        boolean aborted = false;
        int index = 0;
        for (CheckModule module : modules) {
            if (listener.abortRequested()) {
                aborted = true;
                listener.onLog("用户请求中止，已停止后续模块执行");
                break;
            }
            index++;
            tracker.beginNext();
            listener.onModuleStarted(module, index, modules.size());
            listener.onLog(String.format("[%02d/%02d] %s %s", index, modules.size(), module.id(), module.name()));
            ModuleResult result = module.execute(context);
            context.register(result);
            results.add(result);
            listener.onModuleFinished(result);
        }

        TestMeta meta = buildMeta(options, testStart, adminCheck.admin());
        TestStatus testStatus = aborted ? TestStatus.ABORTED : TestStatus.COMPLETED;
        meta.status = testStatus.sourceValue();
        report.testMeta = meta;
        report.testEnvironment = environment;
        report.moduleResults = results;

        // 先汇总外部验证任务，再生成摘要：这样全局诊断里的“外部验证清单共 N 项”
        // 与报告 ManualValidationList 的实际条目数必然一致。
        report.manualValidationList = deriveManualTasks(results);
        report.summary = RuleEngine.summarize(results, adminCheck.admin(), report.manualValidationList.size());
        if (aborted) {
            report.summary.globalDiagnosis = report.summary.globalDiagnosis
                    + "本次诊断被用户中止，未执行的模块不计入统计（" + results.size() + "/" + modules.size() + "）。";
        }

        listener.onStatus(aborted ? "诊断已中止" : "诊断完成");
        return report;
    }

    private TestMeta buildMeta(Options options, Instant testStart, boolean admin) {
        Instant testEnd = Instant.now();
        TestMeta meta = new TestMeta();
        meta.testStartTime = TimeUtil.iso(testStart);
        meta.testEndTime = TimeUtil.iso(testEnd);
        meta.testDuration = TimeUtil.duration(Duration.between(testStart, testEnd));
        meta.status = TestStatus.COMPLETED.sourceValue();
        meta.adminPrivilege = admin;
        meta.tool = BuildInfo.toolName();
        meta.toolVersion = BuildInfo.toolVersion();
        meta.schema = "BlameNet.Report/1.0";
        meta.timeZone = TimeUtil.zoneId();
        meta.locale = java.util.Locale.getDefault().toLanguageTag();
        meta.javaVersion = BuildInfo.runtimeJdk();
        meta.buildTool = BuildInfo.buildToolDescription();
        meta.runProfile = options.profileName();
        meta.moduleFilter = options.moduleFilterText();
        meta.installDirectory = InstallPaths.installDir().toString();
        try {
            meta.resultsDirectory = (options.outputDir != null
                    ? InstallPaths.ensureDir(options.outputDir)
                    : InstallPaths.resultsDir()).toString();
        } catch (Exception e) {
            meta.resultsDirectory = "";
        }
        return meta;
    }

    private TestEnvironment collectEnvironment(DiagnosticContext context, Privilege.AdminCheck adminCheck) {
        TestEnvironment environment = new TestEnvironment();
        List<Map<String, Object>> systems = context.psJson("win32ComputerSystem",
                "@(Get-CimInstance Win32_ComputerSystem | Select-Object Name,Domain,Manufacturer,Model,"
                        + "@{n='TotalMemoryMB';e={[int]($_.TotalPhysicalMemory/1MB)}},UserName) | ConvertTo-Json -Depth 4");
        if (!systems.isEmpty()) {
            Map<String, Object> system = systems.get(0);
            environment.hostName = text(system.get("Name"));
            environment.domain = text(system.get("Domain"));
            environment.manufacturer = text(system.get("Manufacturer"));
            environment.model = text(system.get("Model"));
            environment.userName = text(system.get("UserName"));
            Object memory = system.get("TotalMemoryMB");
            if (memory instanceof Number number) {
                environment.totalMemoryMb = number.longValue();
            }
        }

        List<Map<String, Object>> systemsOs = context.psJson("win32OperatingSystem",
                "@(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,OSArchitecture,"
                        + "SystemDrive,@{n='InstallDate';e={if($_.InstallDate){$_.InstallDate.ToString('yyyy-MM-dd HH:mm:ss')}else{''}}}) "
                        + "| ConvertTo-Json -Depth 4");
        if (!systemsOs.isEmpty()) {
            Map<String, Object> os = systemsOs.get(0);
            environment.osCaption = text(os.get("Caption"));
            environment.osVersion = text(os.get("Version"));
            environment.osBuild = text(os.get("BuildNumber"));
            environment.osArchitecture = text(os.get("OSArchitecture"));
            environment.systemDrive = text(os.get("SystemDrive"));
        }

        environment.timeZoneOffsetMinutes = ZoneOffset.systemDefault().getRules()
                .getOffset(Instant.now()).getTotalSeconds() / 60;

        CmdResult adminEvidence = adminCheck.evidence();
        Evidence evidence = new Evidence("管理员权限判定证据", adminEvidence.command(), adminEvidence.output(),
                adminEvidence.recordTime());
        evidence.exitCode = adminEvidence.executed() ? adminEvidence.exitCode() : null;
        evidence.durationMillis = adminEvidence.durationMillis();
        environment.adminCheckEvidence = evidence;

        environment.probeCommandCount = context.drainCommandCount();
        environment.dataSources = "所有数据均来自本机只读采集：PowerShell（Get-Net*/Get-CimInstance/Get-PnpDevice/Get-Service 等）、"
                + "Windows 原生命令行工具（ipconfig / ping / netsh / route / arp / netstat / powercfg / sfc / DISM / pktmon）、"
                + "以及 hosts 文件只读读取。本工具不执行任何写操作，不修改任何系统配置。";
        return environment;
    }

    private Map<String, String> collectTools(DiagnosticContext context) {
        Map<String, String> tools = new LinkedHashMap<>();
        List<Map<String, Object>> available = context.psJson("toolAvailability",
                "@(Get-Command ipconfig,ping,arp,route,netstat,netsh,powercfg,sfc,dism,pktmon,nslookup,"
                        + "Resolve-DnsName,Test-NetConnection -ErrorAction SilentlyContinue "
                        + "| Select-Object Name,Source,CommandType) | ConvertTo-Json -Depth 4");
        for (Map<String, Object> item : available) {
            String name = text(item.get("Name"));
            String source = text(item.get("Source"));
            if (TextUtil.isBlank(source)) {
                source = "内置命令（" + text(item.get("CommandType")) + "）";
            }
            if (TextUtil.isNotBlank(name)) {
                tools.put(name, source);
            }
        }
        for (String expected : List.of("ipconfig", "ping", "arp", "route", "netstat", "netsh", "powercfg",
                "sfc", "dism", "pktmon", "nslookup")) {
            tools.putIfAbsent(expected, "");
        }
        return tools;
    }

    private Map<String, String> collectShellInfo(DiagnosticContext context) {
        Map<String, String> shell = new LinkedHashMap<>();
        shell.put("PowerShell", PowerShell.executable());
        shell.put("PowerShellArguments", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command <script>");
        shell.put("CMD", Native.shell());
        shell.put("CMDArguments", "/d /s /c <command>");
        shell.put("JavaHome", System.getProperty("java.home", ""));
        shell.put("WorkingDirectory", System.getProperty("user.dir", ""));
        return shell;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 外部验证任务汇总。
     * <p>
     * 由各 Manual 模块自行声明任务内容（物理替换测试、干净启动、PE/Live 对比、重装建议），
     * 引擎只做汇总与排序，保证“外部验证项不跳过”。
     */
    private static List<com.blamenet.model.ManualValidationItem> deriveManualTasks(List<ModuleResult> results) {
        List<com.blamenet.model.ManualValidationItem> items = new ArrayList<>();
        for (ModuleResult result : results) {
            if (result.manualTask != null) {
                items.add(result.manualTask);
            }
        }
        items.sort((left, right) -> Long.compare(
                com.blamenet.model.FaultLayer.parseModuleId(left.moduleId),
                com.blamenet.model.FaultLayer.parseModuleId(right.moduleId)));
        return items;
    }

    /** 以 JSON 形式输出报告（调试用）。 */
    public static String toJson(Report report) {
        return JsonUtil.toPrettyJson(report);
    }
}
