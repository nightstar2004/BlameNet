package com.blamenet.report;

import com.blamenet.model.Report;
import com.blamenet.util.BuildInfo;
import com.blamenet.util.HashUtil;
import com.blamenet.util.JsonUtil;
import com.blamenet.util.TimeUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告包清单（manifest.json）生成器。
 * <p>
 * schema 为 BlameNet.ReportBundle/1.0，files 数组中的 sha256 全部由本机运行时
 * 对真实文件重新计算（{@link HashUtil#sha256(Path)}），不写入任何占位或模拟值；
 * 若某个形态导出失败，会在 failedExports 中如实登记，并说明缺失原因。
 */
public final class ManifestWriter {

    public static final String SCHEMA = "BlameNet.ReportBundle/1.0";
    public static final String FILE_NAME = "manifest.json";

    private ManifestWriter() {
    }

    /** 清单中的单个文件条目。 */
    public record FileEntry(String name, String type, String role, String sha256, long sizeBytes) {
    }

    /** 需登记的导出失败项。 */
    public record Failure(String role, String name, String message) {
    }

    /**
     * 生成并写入 manifest.json。
     *
     * @param report   JSON 主数据源反序列化出的报告
     * @param paths    报告路径规划
     * @param entries  已真实生成并计算过 SHA-256 的文件条目（按展示顺序）
     * @param failures 导出失败项（可为空）
     */
    public static Path write(Report report, ReportPaths paths, List<FileEntry> entries, List<Failure> failures)
            throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", SCHEMA);
        manifest.put("reportId", paths.reportId());
        manifest.put("sourceJson", paths.jsonFileName());
        manifest.put("generatedAt", report.summary.reportGeneratedAt);
        manifest.put("generator", BuildInfo.toolName() + " Report Exporter " + BuildInfo.toolVersion());
        manifest.put("tool", BuildInfo.toolName());
        manifest.put("toolVersion", BuildInfo.toolVersion());
        manifest.put("buildTool", report.testMeta.buildTool);
        manifest.put("javaVersion", report.testMeta.javaVersion);
        manifest.put("testStartTime", report.testMeta.testStartTime);
        manifest.put("testEndTime", report.testMeta.testEndTime);
        manifest.put("testDuration", report.testMeta.testDuration);
        manifest.put("testStatus", report.testMeta.status);
        manifest.put("adminPrivilege", report.testMeta.adminPrivilege);
        manifest.put("riskLevel", report.summary.riskLevel);
        manifest.put("totalModuleCount", report.summary.totalModuleCount);
        manifest.put("passCount", report.summary.passCount);
        manifest.put("warnCount", report.summary.warnCount);
        manifest.put("failCount", report.summary.failCount);
        manifest.put("manualCount", report.summary.manualCount);
        manifest.put("infoCount", report.summary.infoCount);
        manifest.put("evidenceCount", report.evidenceCount());
        manifest.put("manualValidationCount", report.manualValidationList.size());
        manifest.put("zipFileName", paths.zipFileName());
        manifest.put("zipFolder", paths.bundleFolderName());

        List<Map<String, Object>> files = new ArrayList<>();
        for (FileEntry entry : entries) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("name", entry.name());
            file.put("type", entry.type());
            file.put("role", entry.role());
            file.put("sha256", entry.sha256());
            file.put("sizeBytes", entry.sizeBytes());
            files.add(file);
        }
        manifest.put("files", files);

        List<Map<String, Object>> failed = new ArrayList<>();
        for (Failure failure : failures) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", failure.role());
            item.put("name", failure.name());
            item.put("reason", failure.message());
            failed.add(item);
        }
        manifest.put("failedExports", failed);
        manifest.put("complete", failed.isEmpty());

        Path file = paths.directory().resolve(FILE_NAME);
        String json = JsonUtil.toPrettyJson(manifest);
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /** 计算文件真实 SHA-256（供流水线与校验命令使用）。 */
    public static String sha256Of(Path file) throws IOException {
        return HashUtil.sha256(file);
    }

    /** manifest 自身的生成时间戳（与报告生成时间保持同一时钟来源）。 */
    public static String now() {
        return TimeUtil.isoNow();
    }
}
