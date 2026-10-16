package com.blamenet.core;

import com.blamenet.collect.CmdResult;
import com.blamenet.collect.Native;
import com.blamenet.collect.PowerShell;
import com.blamenet.collect.Privilege;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Status;
import com.blamenet.util.JsonUtil;
import com.blamenet.util.TextUtil;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断上下文。
 * <p>
 * 统一承载三件事：
 * <ol>
 *     <li><b>采集</b>：所有命令都通过这里执行，带缓存（同一份数据不重复采集，避免报告自相矛盾）、
 *         带超时、带真实证据记录；</li>
 *     <li><b>进度</b>：模块通过 {@link #step(String)} 上报小步骤，驱动界面底部进度条；</li>
 *     <li><b>结果共享</b>：后置模块与前后的判定规则可以读取已完成的模块结果。</li>
 * </ol>
 */
public final class DiagnosticContext {

    /** 常用超时档位。 */
    public static final Duration TIMEOUT_SHORT = Duration.ofSeconds(30);
    public static final Duration TIMEOUT_MEDIUM = Duration.ofSeconds(60);
    public static final Duration TIMEOUT_LONG = Duration.ofSeconds(150);
    public static final Duration TIMEOUT_PING = Duration.ofSeconds(240);
    public static final Duration TIMEOUT_HEAVY = Duration.ofSeconds(1800);
    public static final Duration TIMEOUT_PKTMON = Duration.ofSeconds(120);

    private final Options options;
    private final ProgressTracker tracker;
    private final boolean admin;
    private final CmdResult adminEvidence;

    private final Map<String, CmdResult> commandCache = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> jsonCache = new HashMap<>();
    private final Map<String, Map<String, Object>> jsonFirstCache = new HashMap<>();
    private final Map<String, ModuleResult> results = new LinkedHashMap<>();

    private int currentCommandCount;

    public DiagnosticContext(Options options, ProgressTracker tracker, boolean admin, CmdResult adminEvidence) {
        this.options = options;
        this.tracker = tracker;
        this.admin = admin;
        this.adminEvidence = adminEvidence;
    }

    // ==================================================================================
    // 进度
    // ==================================================================================

    /** 上报一个小步骤完成，界面进度条立即推进。 */
    public void step(String detail) {
        tracker.step(detail);
    }

    // ==================================================================================
    // 采集
    // ==================================================================================

    /** 执行 PowerShell 采集脚本（结果缓存，key 相同则复用真实采集结果）。 */
    public CmdResult ps(String key, String script) {
        return ps(key, script, TIMEOUT_MEDIUM);
    }

    /** 执行 PowerShell 采集脚本。 */
    public CmdResult ps(String key, String script, Duration timeout) {
        CmdResult cached = commandCache.get(key);
        if (cached != null) {
            return cached;
        }
        CmdResult result = PowerShell.run(script, options.scale(timeout));
        commandCache.put(key, result);
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 执行原生命令（结果缓存）。 */
    public CmdResult cmd(String key, String commandLine) {
        return cmd(key, commandLine, TIMEOUT_MEDIUM);
    }

    /** 执行原生命令。 */
    public CmdResult cmd(String key, String commandLine, Duration timeout) {
        return cmd(key, commandLine, timeout, null);
    }

    /** 执行原生命令并指定工作目录（例如 pktmon 需要在无空格路径下产生捕获文件）。 */
    public CmdResult cmd(String key, String commandLine, Duration timeout, Path workingDirectory) {
        CmdResult cached = commandCache.get(key);
        if (cached != null) {
            return cached;
        }
        CmdResult result = com.blamenet.collect.Cmd.exec(
                java.util.List.of(com.blamenet.collect.Native.shell(), "/d", "/s", "/c", commandLine),
                commandLine, options.scale(timeout), workingDirectory);
        commandCache.put(key, result);
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 执行原生命令（不缓存），用于需要临时目录等一次性场景。 */
    public CmdResult cmdNoCache(String commandLine, Duration timeout, Path workingDirectory) {
        CmdResult result = com.blamenet.collect.Cmd.exec(
                java.util.List.of(com.blamenet.collect.Native.shell(), "/d", "/s", "/c", commandLine),
                commandLine, options.scale(timeout), workingDirectory);
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 只读读取本地文件（例如 hosts）并生成证据。 */
    public CmdResult file(String key, Path file, String evidenceCommand) {
        CmdResult cached = commandCache.get(key);
        if (cached != null) {
            return cached;
        }
        CmdResult result = Privilege.readTextFile(file, evidenceCommand);
        commandCache.put(key, result);
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 执行 PowerShell 脚本并把输出解析为对象列表。 */
    public List<Map<String, Object>> psJson(String key, String script) {
        return psJson(key, script, TIMEOUT_MEDIUM);
    }

    /** 执行 PowerShell 脚本并把输出解析为对象列表。 */
    public List<Map<String, Object>> psJson(String key, String script, Duration timeout) {
        List<Map<String, Object>> cached = jsonCache.get(key);
        if (cached != null) {
            return cached;
        }
        CmdResult result = ps(key, script, timeout);
        List<Map<String, Object>> parsed = JsonUtil.toObjectList(result.stdout());
        jsonCache.put(key, parsed);
        return parsed;
    }

    /** 执行 PowerShell 脚本并取第一条记录。 */
    public Map<String, Object> psJsonFirst(String key, String script) {
        return psJsonFirst(key, script, TIMEOUT_MEDIUM);
    }

    /** 执行 PowerShell 脚本并取第一条记录。 */
    public Map<String, Object> psJsonFirst(String key, String script, Duration timeout) {
        Map<String, Object> cached = jsonFirstCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> list = psJson(key, script, timeout);
        Map<String, Object> first = list.isEmpty() ? new LinkedHashMap<>() : list.get(0);
        jsonFirstCache.put(key, first);
        return first;
    }

    /** 直接执行一条命令并计数（用于需要按参数多次执行且不缓存的场景，例如 MTU 逐级探测）。 */
    public CmdResult cmdNoCache(String commandLine, Duration timeout) {
        CmdResult result = Native.run(commandLine, options.scale(timeout));
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 直接执行一条 PowerShell 脚本并计数（不缓存）。 */
    public CmdResult psNoCache(String script, Duration timeout) {
        CmdResult result = PowerShell.run(script, options.scale(timeout));
        if (result.executed()) {
            currentCommandCount++;
        }
        return result;
    }

    /** 取出本模块真实执行的命令条数并复位。 */
    public int drainCommandCount() {
        int count = currentCommandCount;
        currentCommandCount = 0;
        return count;
    }

    /** 已采集到的命令 key 列表，便于诊断与审计。 */
    public List<String> collectedKeys() {
        return new ArrayList<>(commandCache.keySet());
    }

    // ==================================================================================
    // 结果共享
    // ==================================================================================

    public void register(ModuleResult result) {
        if (result != null && TextUtil.isNotBlank(result.moduleId)) {
            results.put(result.moduleId, result);
        }
    }

    public ModuleResult resultOf(String moduleId) {
        return results.get(moduleId);
    }

    public Status statusOf(String moduleId) {
        ModuleResult result = results.get(moduleId);
        return result == null ? Status.INFO : Status.fromSource(result.status);
    }

    public boolean isFail(String moduleId) {
        return statusOf(moduleId) == Status.FAIL;
    }

    public boolean isWarn(String moduleId) {
        return statusOf(moduleId) == Status.WARN;
    }

    public boolean isProblem(String moduleId) {
        return statusOf(moduleId).isProblem();
    }

    /** 读取已执行模块的证据中的原始输出（供跨模块判定使用）。 */
    public String evidenceOutputOf(String moduleId, int index) {
        ModuleResult result = results.get(moduleId);
        if (result == null || result.evidences == null || result.evidences.size() <= index) {
            return "";
        }
        return TextUtil.nvl(result.evidences.get(index).rawOutput);
    }

    // ==================================================================================
    // 访问器
    // ==================================================================================

    public Options options() {
        return options;
    }

    public boolean admin() {
        return admin;
    }

    public CmdResult adminEvidence() {
        return adminEvidence;
    }

    public ProgressTracker tracker() {
        return tracker;
    }
}
