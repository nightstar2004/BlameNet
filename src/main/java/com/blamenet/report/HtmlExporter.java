package com.blamenet.report;

import com.blamenet.model.Evidence;
import com.blamenet.model.ManualValidationItem;
import com.blamenet.model.ModuleResult;
import com.blamenet.model.Report;
import com.blamenet.model.RiskLevel;
import com.blamenet.model.Status;
import com.blamenet.model.TestStatus;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.TextUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTML 报表导出器。
 * <p>
 * 视觉结构遵循设计要求给出的示例（卡片式布局、状态徽章、可折叠证据块），并在此基础上补充：
 * 模块总表、环境与预检证据、证据说明、隐私提示与免责声明，便于普通用户直接阅读与打印。
 */
public final class HtmlExporter implements Exporter {

    @Override
    public String id() {
        return "html";
    }

    @Override
    public String displayName() {
        return "网页报告（HTML）";
    }

    @Override
    public String extension() {
        return "html";
    }

    @Override
    public String manifestRole() {
        return "human-readable-report";
    }

    @Override
    public String mimeType() {
        return "text/html";
    }

    @Override
    public Path export(Report report, ReportPaths paths) throws Exception {
        String html = render(report, paths);
        Path file = paths.file(extension());
        Files.writeString(file, html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /** 渲染完整 HTML。 */
    public static String render(Report report, ReportPaths paths) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        RiskLevel risk = RiskLevel.fromSource(report.summary.riskLevel);
        TestStatus testStatus = TestStatus.fromSource(report.testMeta.status);

        sb.append("<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<meta name=\"generator\" content=\"").append(TextUtil.escapeHtml(BuildInfo.toolName())).append("\">\n");
        sb.append("<title>BlameNet 网络诊断报告 - ").append(TextUtil.escapeHtml(paths.reportId())).append("</title>\n");
        sb.append(style());
        sb.append("</head>\n<body>\n<div class=\"page\">\n");

        // 封面
        sb.append("  <header class=\"hero\">\n    <div>\n");
        sb.append("      <p>BlameNet 网络诊断报告</p>\n");
        sb.append("      <h1>网络诊断报告</h1>\n");
        sb.append("      <p>报告编号：").append(TextUtil.escapeHtml(paths.reportId())).append("</p>\n");
        sb.append("      <p>测试状态：").append(TextUtil.escapeHtml(testStatus.chinese()))
                .append("（源值 ").append(TextUtil.escapeHtml(report.testMeta.status)).append("）</p>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"risk ").append(risk.name().toLowerCase()).append("\">风险等级：")
                .append(TextUtil.escapeHtml(risk.chinese())).append("</div>\n  </header>\n");

        // 目录
        sb.append("  <nav class=\"toc\">\n");
        sb.append("    <a href=\"#meta\">测试元数据</a><a href=\"#summary\">摘要</a>")
                .append("<a href=\"#env\">环境信息</a><a href=\"#table\">模块总表</a>")
                .append("<a href=\"#modules\">模块详情</a><a href=\"#manual\">手动验证清单</a>")
                .append("<a href=\"#appendix\">声明</a>\n");
        sb.append("  </nav>\n");

        // 测试元数据（源值原样）
        sb.append("  <section class=\"section\" id=\"meta\">\n    <h2>测试元数据</h2>\n");
        sb.append("    <div class=\"meta-grid\">\n");
        card(sb, "开始时间", report.testMeta.testStartTime, true);
        card(sb, "结束时间", report.testMeta.testEndTime, true);
        card(sb, "耗时", report.testMeta.testDuration, true);
        card(sb, "管理员权限", report.testMeta.adminPrivilege ? "是" : "否", false);
        sb.append("    </div>\n");
        sb.append("    <table class=\"kv\">\n<tbody>\n");
        kv(sb, "TestStartTime", report.testMeta.testStartTime);
        kv(sb, "TestEndTime", report.testMeta.testEndTime);
        kv(sb, "TestDuration", report.testMeta.testDuration);
        kv(sb, "Status", report.testMeta.status);
        kv(sb, "AdminPrivilege", String.valueOf(report.testMeta.adminPrivilege));
        sb.append("</tbody>\n</table>\n");
        sb.append("    <p class=\"hint\">以上 5 个字段为源数据字段，展示时保持原样，不做翻译与改写。</p>\n");
        sb.append("  </section>\n");

        // 摘要
        sb.append("  <section class=\"section\" id=\"summary\">\n    <h2>摘要</h2>\n");
        sb.append("    <div class=\"summary-grid\">\n");
        card(sb, "模块总数", String.valueOf(report.summary.totalModuleCount), false);
        card(sb, "通过", String.valueOf(report.summary.passCount), false, "pass");
        card(sb, "警告", String.valueOf(report.summary.warnCount), false, "warn");
        card(sb, "失败", String.valueOf(report.summary.failCount), false, "fail");
        card(sb, "手动", String.valueOf(report.summary.manualCount), false, "manual");
        card(sb, "手动验证任务数", String.valueOf(report.summary.manualValidationCount), false);
        card(sb, "信息类", String.valueOf(report.summary.infoCount), false, "info");
        card(sb, "测试状态", testStatus.chinese(), false);
        card(sb, "风险等级", risk.chinese(), false, risk == RiskLevel.LOW ? "pass" : "fail");
        card(sb, "证据条数", String.valueOf(report.evidenceCount()), false);
        card(sb, "报告生成时间", TextUtil.nvl(report.summary.reportGeneratedAt), true);
        sb.append("    </div>\n");
        sb.append("    <div class=\"diagnosis\"><strong>全局诊断：</strong>")
                .append(TextUtil.escapeHtml(report.summary.globalDiagnosis)).append("</div>\n");
        if (report.summary.faultLayers != null && !report.summary.faultLayers.isEmpty()) {
            sb.append("    <div class=\"tags\">故障层定位：");
            for (String layer : report.summary.faultLayers) {
                sb.append("<span class=\"tag\">").append(TextUtil.escapeHtml(layer)).append("</span>");
            }
            sb.append("</div>\n");
        }
        if (report.summary.failingModules != null && !report.summary.failingModules.isEmpty()) {
            sb.append("    <p class=\"hint\">失败模块：").append(TextUtil.escapeHtml(String.join("；", report.summary.failingModules)))
                    .append("</p>\n");
        }
        if (report.summary.warningModules != null && !report.summary.warningModules.isEmpty()) {
            sb.append("    <p class=\"hint\">警告模块：").append(TextUtil.escapeHtml(String.join("；", report.summary.warningModules)))
                    .append("</p>\n");
        }
        sb.append("  </section>\n");

        // 环境信息
        sb.append("  <section class=\"section\" id=\"env\">\n    <h2>测试环境与预检证据</h2>\n");
        sb.append("    <table class=\"kv\">\n<tbody>\n");
        kv(sb, "主机名", report.testEnvironment.hostName);
        kv(sb, "当前用户", report.testEnvironment.userName);
        kv(sb, "域 / 工作组", report.testEnvironment.domain);
        kv(sb, "设备型号", join(report.testEnvironment.manufacturer, report.testEnvironment.model));
        kv(sb, "操作系统", join(report.testEnvironment.osCaption, report.testEnvironment.osVersion,
                report.testEnvironment.osBuild, report.testEnvironment.osArchitecture));
        kv(sb, "系统盘", report.testEnvironment.systemDrive);
        kv(sb, "物理内存", report.testEnvironment.totalMemoryMb == null ? ""
                : report.testEnvironment.totalMemoryMb + " MB");
        kv(sb, "时区偏移", report.testEnvironment.timeZoneOffsetMinutes == null ? ""
                : report.testEnvironment.timeZoneOffsetMinutes + " 分钟");
        kv(sb, "工具版本", TextUtil.nvl(report.testMeta.tool) + " " + TextUtil.nvl(report.testMeta.toolVersion));
        kv(sb, "运行档位", TextUtil.nvl(report.testMeta.runProfile));
        kv(sb, "模块过滤", TextUtil.nvl(report.testMeta.moduleFilter));
        kv(sb, "运行 JDK", TextUtil.nvl(report.testMeta.javaVersion));
        kv(sb, "构建工具", TextUtil.nvl(report.testMeta.buildTool));
        sb.append("</tbody>\n</table>\n");

        if (report.testEnvironment.toolAvailability != null && !report.testEnvironment.toolAvailability.isEmpty()) {
            sb.append("    <h3>关键工具可用性</h3>\n    <table class=\"kv\"><tbody>\n");
            for (Map.Entry<String, String> entry : report.testEnvironment.toolAvailability.entrySet()) {
                kv(sb, entry.getKey(), TextUtil.isBlank(entry.getValue()) ? "不可用" : entry.getValue());
            }
            sb.append("</tbody>\n</table>\n");
        }
        if (report.testEnvironment.collectorShell != null && !report.testEnvironment.collectorShell.isEmpty()) {
            sb.append("    <h3>采集外壳</h3>\n    <table class=\"kv\"><tbody>\n");
            for (Map.Entry<String, String> entry : report.testEnvironment.collectorShell.entrySet()) {
                kv(sb, entry.getKey(), entry.getValue());
            }
            sb.append("</tbody>\n</table>\n");
        }
        if (report.testEnvironment.adminCheckEvidence != null) {
            evidence(sb, report.testEnvironment.adminCheckEvidence);
        }
        sb.append("  </section>\n");

        // 模块总表
        sb.append("  <section class=\"section\" id=\"table\">\n    <h2>模块总表</h2>\n");
        sb.append("    <table class=\"grid\">\n      <thead><tr>")
                .append("<th>编号</th><th>模块名称</th><th>状态</th><th>摘要</th></tr></thead>\n      <tbody>\n");
        for (ModuleResult module : report.moduleResults) {
            Status status = Status.fromSource(module.status);
            sb.append("        <tr><td class=\"mono\">").append(TextUtil.escapeHtml(module.moduleId)).append("</td>")
                    .append("<td><a href=\"#m").append(TextUtil.escapeHtml(module.moduleId)).append("\">")
                    .append(TextUtil.escapeHtml(module.moduleName)).append("</a></td>")
                    .append("<td><span class=\"badge ").append(status.name().toLowerCase()).append("\">")
                    .append(TextUtil.escapeHtml(status.chinese())).append("</span></td>")
                    .append("<td>").append(TextUtil.escapeHtml(module.summary)).append("</td></tr>\n");
        }
        sb.append("      </tbody>\n    </table>\n  </section>\n");

        // 模块详情
        sb.append("  <section class=\"section\" id=\"modules\">\n    <h2>模块详情</h2>\n");
        for (ModuleResult module : report.moduleResults) {
            module(sb, module);
        }
        sb.append("  </section>\n");

        // 手动验证清单
        sb.append("  <section class=\"section\" id=\"manual\">\n    <h2>手动验证清单（共 ")
                .append(report.manualValidationList == null ? 0 : report.manualValidationList.size())
                .append(" 项）</h2>\n");
        if (report.manualValidationList == null || report.manualValidationList.isEmpty()) {
            sb.append("    <p class=\"hint\">本次诊断没有产生外部验证任务。</p>\n");
        } else {
            for (ManualValidationItem item : report.manualValidationList) {
                sb.append("    <article class=\"module\">\n      <div class=\"module-head\"><div>")
                        .append("<div class=\"module-title\">").append(TextUtil.escapeHtml(item.moduleId))
                        .append(" ").append(TextUtil.escapeHtml(item.taskTitle)).append("</div>")
                        .append("<div class=\"mono muted\">模块编号：").append(TextUtil.escapeHtml(item.moduleId))
                        .append("</div></div>")
                        .append("<span class=\"badge ").append(item.isFilled() ? "pass" : "manual").append("\">")
                        .append(item.isFilled() ? "已回填" : "待填写").append("</span></div>\n")
                        .append("      <p><strong>操作说明：</strong>").append(TextUtil.escapeHtml(item.instructions)).append("</p>\n")
                        .append("      <p><strong>优先级：</strong>").append(TextUtil.escapeHtml(TextUtil.nvl(item.priority))).append("</p>\n")
                        .append("      <p><strong>用户填写结果：</strong>")
                        .append(item.isFilled()
                                ? TextUtil.escapeHtml(item.userFillResult)
                                : "<span class=\"muted\">null / 待填写</span>")
                        .append("</p>\n");
                if (item.isFilled() && TextUtil.isNotBlank(item.filledAt)) {
                    sb.append("      <p><strong>回填时间：</strong><span class=\"mono\">")
                            .append(TextUtil.escapeHtml(item.filledAt)).append("</span></p>\n");
                }
                sb.append("    </article>\n");
            }
        }
        sb.append("  </section>\n");

        // 声明
        sb.append("  <section class=\"section\" id=\"appendix\">\n    <h2>声明</h2>\n");

        sb.append("    <h3>证据说明</h3>\n    <ul class=\"plain\">\n");
        sb.append("      <li>PowerShell 采集统一以 <span class=\"mono\">powershell.exe -NoProfile -NonInteractive ")
                .append("-ExecutionPolicy Bypass -Command &lt;script&gt;</span> 方式执行，脚本开头额外设置输出编码为 UTF-8。</li>\n");
        sb.append("      <li>原生命令统一以 <span class=\"mono\">cmd.exe /d /s /c &lt;command&gt;</span> 方式执行。</li>\n");
        sb.append("      <li>证据中的 RawCommand 为等价的原生命令文本；RawOutput 为运行时的真实输出（超长时截断并标注）。</li>\n");
        sb.append("      <li>本工具只做只读诊断，不执行任何写操作，不修改网络配置、注册表、服务状态与系统文件。</li>\n");
        sb.append("    </ul>\n");

        sb.append("    <h3>隐私与脱敏提示</h3>\n");
        sb.append("    <p class=\"hint\">报告可能包含 MAC 地址、IP 地址、默认网关、DNS 服务器、主机名、Wi-Fi SSID、")
                .append("用户名与文件路径等敏感信息。分享报告前请根据实际需要脱敏。</p>\n");

        sb.append("    <h3>校验与完整性</h3>\n");
        sb.append("    <p class=\"hint\">包内文件清单与每个文件的角色、类型、真实 SHA-256 记录在 README.txt 与 ")
                .append("manifest.json 中。可在 PowerShell 中执行 ")
                .append("<span class=\"mono\">Get-FileHash .\\").append(TextUtil.escapeHtml(paths.jsonFileName()))
                .append(" -Algorithm SHA256</span> 与 manifest.json 中的值比对。</p>\n");

        sb.append("    <h3>免责声明</h3>\n");
        sb.append("    <p class=\"hint\">本软件可用于个人自有网络学习与故障自查，也允许企业在自有且已获授权的内部环境中")
                .append("用于商用设备网络故障诊断，但禁止将本软件或其修改版本单独作为商品销售或有偿授权。")
                .append("软件按现状提供，不保证检测结果完全准确。用户不得使用本软件对非授权设备进行探测、扫描等操作；")
                .append("违规使用带来的法律后果全部由使用者自行承担。</p>\n");
        sb.append("  </section>\n");

        sb.append("  <footer>\n    由 BlameNet 从唯一 JSON 数据源生成 · 生成时间 ")
                .append(TextUtil.escapeHtml(TextUtil.nvl(report.summary.reportGeneratedAt)))
                .append(" · 主数据源 ").append(TextUtil.escapeHtml(paths.jsonFileName()))
                .append("\n  </footer>\n");
        sb.append("</div>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static void module(StringBuilder sb, ModuleResult module) {
        Status status = Status.fromSource(module.status);
        sb.append("    <article class=\"module\" id=\"m").append(TextUtil.escapeHtml(module.moduleId)).append("\">\n");
        sb.append("      <div class=\"module-head\">\n        <div>\n");
        sb.append("          <div class=\"module-title\">").append(TextUtil.escapeHtml(module.moduleId)).append(" ")
                .append(TextUtil.escapeHtml(module.moduleName)).append("</div>\n");
        sb.append("          <div class=\"mono muted\">模块编号：").append(TextUtil.escapeHtml(module.moduleId))
                .append(" · 分类：").append(TextUtil.escapeHtml(TextUtil.nvl(module.category)))
                .append(" · 耗时：").append(TextUtil.escapeHtml(TextUtil.nvl(module.duration)))
                .append(" · 执行命令：").append(module.executedCommandCount).append(" 条")
                .append(module.requiresAdmin ? " · 需要管理员权限" : "")
                .append("</div>\n        </div>\n");
        sb.append("        <span class=\"badge ").append(status.name().toLowerCase()).append("\">")
                .append(TextUtil.escapeHtml(status.chinese())).append("</span>\n      </div>\n");
        sb.append("      <p><strong>摘要：</strong>").append(TextUtil.escapeHtml(module.summary)).append("</p>\n");
        sb.append("      <p><strong>诊断：</strong>").append(TextUtil.escapeHtml(module.diagnosis)).append("</p>\n");
        sb.append("      <p><strong>建议：</strong>").append(TextUtil.escapeHtml(module.suggestion)).append("</p>\n");
        if (module.manualVerified != null && module.manualVerified) {
            sb.append("      <p><strong>用户回填：</strong>")
                    .append(TextUtil.escapeHtml(TextUtil.nvl(module.manualNote))).append("</p>\n");
        }
        List<Evidence> evidences = module.evidences == null ? new ArrayList<>() : module.evidences;
        for (Evidence evidence : evidences) {
            evidence(sb, evidence);
        }
        sb.append("    </article>\n");
    }

    private static void evidence(StringBuilder sb, Evidence evidence) {
        sb.append("      <details class=\"evidence\">\n        <summary>证据：")
                .append(TextUtil.escapeHtml(evidence.evidenceName)).append("</summary>\n");
        sb.append("        <p><strong>原始命令：</strong></p>\n        <pre>")
                .append(TextUtil.escapeHtml(evidence.rawCommand)).append("</pre>\n");
        sb.append("        <p><strong>原始输出：</strong></p>\n        <pre>")
                .append(TextUtil.escapeHtml(evidence.rawOutput)).append("</pre>\n");
        sb.append("        <p class=\"mono muted\">记录时间：").append(TextUtil.escapeHtml(evidence.recordTime));
        if (evidence.exitCode != null) {
            sb.append(" · 退出码：").append(evidence.exitCode);
        }
        if (Boolean.TRUE.equals(evidence.timedOut)) {
            sb.append(" · 已超时强制结束");
        }
        if (evidence.durationMillis != null) {
            sb.append(" · 执行耗时：").append(evidence.durationMillis).append(" ms");
        }
        if (Boolean.TRUE.equals(evidence.truncated)) {
            sb.append(" · 输出已截断（原始长度 ").append(evidence.originalOutputLength).append(" 字符）");
        }
        sb.append("</p>\n      </details>\n");
    }

    private static void card(StringBuilder sb, String label, String value, boolean mono) {
        card(sb, label, value, mono, null);
    }

    private static void card(StringBuilder sb, String label, String value, boolean mono, String colorClass) {
        sb.append("      <div class=\"card\"><div class=\"label\">").append(TextUtil.escapeHtml(label))
                .append("</div><div class=\"value").append(mono ? " mono" : "");
        if (colorClass != null) {
            sb.append("\" style=\"color:var(--").append(colorClass).append(")");
        }
        sb.append("\">").append(TextUtil.escapeHtml(value)).append("</div></div>\n");
    }

    private static void kv(StringBuilder sb, String key, String value) {
        sb.append("  <tr><th>").append(TextUtil.escapeHtml(key)).append("</th><td>")
                .append(TextUtil.escapeHtml(value)).append("</td></tr>\n");
    }

    private static String join(String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (TextUtil.isNotBlank(part)) {
                values.add(part.trim());
            }
        }
        return String.join(" ", values);
    }

    private static String style() {
        return """
                  <style>
                    :root{
                      --bg:#f5f7fb; --card:#ffffff; --text:#172033; --muted:#667085;
                      --pass:#16a34a; --warn:#d97706; --fail:#dc2626; --manual:#2563eb; --info:#0ea5e9;
                      --border:#e4e7ec; --code:#0f172a;
                    }
                    *{box-sizing:border-box}
                    body{margin:0;background:var(--bg);color:var(--text);
                      font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;line-height:1.6}
                    .page{max-width:1180px;margin:0 auto;padding:28px}
                    .hero{background:linear-gradient(135deg,#111827,#1d4ed8);color:#fff;
                      border-radius:18px;padding:30px;display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap}
                    .hero h1{margin:0 0 8px;font-size:32px}
                    .hero p{margin:0;color:#dbeafe}
                    .risk{padding:10px 16px;border-radius:999px;font-weight:700;height:max-content}
                    .risk.critical{background:#fee2e2;color:#991b1b}
                    .risk.high{background:#ffedd5;color:#9a3412}
                    .risk.medium{background:#fef3c7;color:#92400e}
                    .risk.low{background:#dcfce7;color:#166534}
                    .toc{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
                    .toc a{background:#fff;border:1px solid var(--border);border-radius:999px;
                      padding:6px 14px;color:#1d4ed8;text-decoration:none;font-size:14px}
                    .toc a:hover{background:#eff6ff}
                    .meta-grid,.summary-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:18px}
                    .card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px}
                    .card .label{color:var(--muted);font-size:13px}
                    .card .value{font-size:20px;font-weight:700;margin-top:4px;word-break:break-all}
                    .section{margin-top:26px}
                    .section h2{font-size:22px;margin:0 0 12px}
                    .section h3{font-size:16px;margin:20px 0 8px}
                    .diagnosis{background:#fff7ed;border-left:5px solid #f97316;padding:16px;border-radius:10px;margin-top:14px}
                    .tags{margin-top:10px}
                    .tag{display:inline-block;background:#eef2ff;color:#3730a3;border-radius:999px;
                      padding:4px 12px;margin:4px 6px 0 0;font-size:13px}
                    .module{background:#fff;border:1px solid var(--border);border-radius:14px;padding:18px;margin-bottom:14px}
                    .module-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
                    .module-title{font-size:18px;font-weight:700}
                    .badge{padding:5px 10px;border-radius:999px;font-size:13px;font-weight:700;white-space:nowrap}
                    .badge.pass{background:#dcfce7;color:#166534}
                    .badge.fail{background:#fee2e2;color:#991b1b}
                    .badge.warn{background:#fef3c7;color:#92400e}
                    .badge.manual{background:#dbeafe;color:#1e40af}
                    .badge.info{background:#e0f2fe;color:#075985}
                    .module p{margin:8px 0}
                    .evidence{margin-top:12px;border:1px solid var(--border);border-radius:10px;overflow:hidden}
                    .evidence summary{cursor:pointer;background:#f9fafb;padding:10px 12px;font-weight:600}
                    .evidence pre{margin:0;padding:14px;background:var(--code);color:#e5e7eb;
                      overflow:auto;font-size:13px;font-family:Consolas,"Courier New",monospace;max-height:420px}
                    .evidence p{margin:10px 12px}
                    .mono{font-family:Consolas,"Courier New",monospace}
                    .muted{color:var(--muted)}
                    .hint{color:var(--muted);font-size:14px}
                    table.kv,table.grid{width:100%;border-collapse:collapse;background:#fff;
                      border:1px solid var(--border);border-radius:12px;overflow:hidden}
                    table.grid{margin-top:6px}
                    table.kv th,table.kv td,table.grid th,table.grid td{
                      border-bottom:1px solid var(--border);padding:8px 12px;text-align:left;font-size:14px;vertical-align:top}
                    table.kv th{width:220px;background:#f9fafb;font-weight:600}
                    table.grid th{background:#f9fafb;font-weight:600;white-space:nowrap}
                    table.grid a{color:#1d4ed8;text-decoration:none}
                    ul.plain{margin:0;padding-left:20px;color:#344054}
                    footer{color:var(--muted);font-size:13px;margin:28px 0;text-align:center}
                    @media print{
                      body{background:#fff}
                      .toc{display:none}
                      .evidence pre{max-height:none;background:#f3f4f6;color:#111827}
                      .module{break-inside:avoid}
                      .section{break-inside:auto}
                    }
                    @media (max-width:900px){
                      .meta-grid,.summary-grid{grid-template-columns:repeat(2,1fr)}
                    }
                  </style>
                """;
    }
}
