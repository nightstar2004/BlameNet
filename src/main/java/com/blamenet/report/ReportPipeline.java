package com.blamenet.report;

import com.blamenet.core.EngineListener;
import com.blamenet.core.Options;
import com.blamenet.core.ProgressTracker;
import com.blamenet.model.Report;
import com.blamenet.util.HashUtil;
import com.blamenet.util.InstallPaths;
import com.blamenet.util.TextUtil;
import com.blamenet.util.TimeUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 报告流水线：把内存报告固化为 JSON 主数据源，再由 JSON 同源生成全部导出形态并打包。
 * <p>
 * 严格遵循要求文档“5. 同源生成规则”：
 * <pre>
 * BlameNet-yyyy-MM-dd-HH-mm-ss.json   ← 唯一主数据源
 *         │
 *         ├── HTML / PDF / MD / TXT / XLSX
 *         ├── README.txt   ← 说明文件（同样由 JSON 生成）
 *         └── ZIP          ← 打包以上全部 + manifest.json
 * </pre>
 * 关键实现约束：
 * <ul>
 *     <li>JSON 先落盘，随后所有导出器只接收 {@link JsonStore#read(Path)} 反序列化出的报告对象，
 *         导出层没有能力再次采集数据，从结构上杜绝不同形态之间信息不一致；</li>
 *     <li>JSON 落盘之后不再修改报告对象，因此 JSON 与其它形态的内容严格一致；</li>
 *     <li>任何单个形态导出失败都不中断流水线，失败原因如实记入 manifest.json 的 failedExports；</li>
 *     <li>每个小步骤都调用一次 {@link ProgressTracker#step(String)}，进度条必定推进。</li>
 * </ul>
 */
public final class ReportPipeline {

    private static final List<Exporter> EXPORTERS = List.of(
            new HtmlExporter(),
            new PdfExporter(),
            new PdfFullExporter(),
            new MdExporter(),
            new TxtExporter(),
            new XlsxExporter());

    private ReportPipeline() {
    }

    /** 流水线产出物。 */
    public record Artifact(String role, String displayName, Path path, String sha256, long sizeBytes) {
    }

    /** 导出失败项（如实登记，不隐藏）。 */
    public record Failure(String role, String displayName, String message) {
    }

    /** 一次报告流水线的完整结果。 */
    public record Bundle(String reportId,
                         Path directory,
                         Path jsonFile,
                         Path zipFile,
                         List<Artifact> artifacts,
                         List<Failure> failures,
                         boolean complete) {

        /** 按角色取得产出物路径（不存在返回 null）。 */
        public Path pathOf(String role) {
            for (Artifact artifact : artifacts) {
                if (artifact.role().equals(role)) {
                    return artifact.path();
                }
            }
            return null;
        }

        public boolean hasFailure() {
            return !failures.isEmpty();
        }
    }

    /** 正常诊断流程：写入 JSON 主数据源，再由 JSON 同源生成全部报表并打包。 */
    public static Bundle run(Report report, Options options, ProgressTracker tracker, EngineListener listener)
            throws Exception {
        tracker.beginNext();
        Path resultsDir = options.outputDir != null
                ? InstallPaths.ensureDir(options.outputDir)
                : InstallPaths.resultsDir();
        ReportPaths paths = ReportPaths.create(resultsDir, reportInstant(report));

        // 报告编号等标识必须在 JSON 落盘之前写入，落盘之后报告对象不再改动。
        report.testMeta.reportId = paths.reportId();
        report.summary.reportGeneratedAt = TimeUtil.isoNow();
        report.summary.dataSourceJson = paths.jsonFileName();

        tracker.step("创建结果目录并分配报告编号 " + paths.reportId());
        listener.onLog("结果目录：" + paths.directory());
        listener.onLog("报告编号：" + paths.reportId());

        Path jsonFile = JsonStore.write(report, paths);
        String jsonSha256 = HashUtil.sha256(jsonFile);
        long jsonSize = Files.size(jsonFile);
        tracker.step("写入 JSON 主数据源 " + paths.jsonFileName() + "（" + TextUtil.humanSize(jsonSize) + "）");
        listener.onLog("已写入 JSON 主数据源：" + jsonFile);

        // 之后的全部导出只使用这一份反序列化结果。
        Report source = JsonStore.read(jsonFile);
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact("master-data-source", "JSON 主数据源", jsonFile, jsonSha256, jsonSize));
        List<Failure> failures = new ArrayList<>();

        exportAll(source, paths, tracker, listener, options.skipReports, artifacts, failures);
        return finishBundle(paths, jsonFile, artifacts, failures, listener);
    }

    /**
     * 仅重建报表：从已有 JSON 主数据源重新生成 HTML/PDF/MD/TXT/XLSX/README/manifest/ZIP。
     * <p>
     * 不重新采集、不改写 JSON，因此重建结果与该 JSON 严格一致。
     */
    public static Bundle rebuild(String jsonPath, Options options, ProgressTracker tracker, EngineListener listener)
            throws Exception {
        tracker.beginNext();
        Path jsonFile = Path.of(jsonPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jsonFile)) {
            throw new java.io.FileNotFoundException("找不到 JSON 主数据源：" + jsonFile);
        }
        tracker.step("读取已有 JSON 主数据源 " + jsonFile.getFileName());
        Report source = JsonStore.read(jsonFile);
        tracker.step("校验主数据源结构（TestMeta / Summary / ModuleResults / ManualValidationList）");

        ReportPaths paths = ReportPaths.forExisting(jsonFile);
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact("master-data-source", "JSON 主数据源", jsonFile,
                HashUtil.sha256(jsonFile), Files.size(jsonFile)));
        List<Failure> failures = new ArrayList<>();

        exportAll(source, paths, tracker, listener, false, artifacts, failures);
        return finishBundle(paths, jsonFile, artifacts, failures, listener);
    }

    /**
     * 选择性导出（GUI 导出向导）：按用户勾选的格式，把已有报告导出到指定目录下的
     * {@code BlameNet-<编号>} 子文件夹，并生成 README.txt 与 manifest.json、可打包 ZIP。
     * <p>
     * JSON 主数据源始终写入（作为唯一数据源）；{@code formats} 用导出器 id 表示勾选的展示形态
     * （{@code html/pdf/pdf-full/md/txt/xlsx}），{@code includeZip} 决定是否额外打包 ZIP。
     * README.txt 与 manifest.json 始终生成，用于描述本次导出包含的文件。
     */
    public static Bundle exportSelected(Report report, Set<String> formats, boolean includeZip,
                                        Path targetDirectory, ProgressTracker tracker, EngineListener listener)
            throws Exception {
        tracker.beginNext();
        ReportPaths paths = ReportPaths.create(targetDirectory, reportInstant(report), "报告导出");

        // 报告编号等标识在 JSON 落盘前写入；此后不再改动报告对象。
        report.testMeta.reportId = paths.reportId();
        report.summary.reportGeneratedAt = TimeUtil.isoNow();
        report.summary.dataSourceJson = paths.jsonFileName();

        tracker.step("创建导出子文件夹并分配报告编号 " + paths.reportId());
        listener.onLog("导出目录：" + paths.directory());

        Path jsonFile = JsonStore.write(report, paths);
        String jsonSha256 = HashUtil.sha256(jsonFile);
        long jsonSize = Files.size(jsonFile);
        tracker.step("写入 JSON 主数据源 " + paths.jsonFileName() + "（" + TextUtil.humanSize(jsonSize) + "）");

        Report source = JsonStore.read(jsonFile);
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact("master-data-source", "JSON 主数据源", jsonFile, jsonSha256, jsonSize));
        List<Failure> failures = new ArrayList<>();

        // 仅导出被勾选的展示形态。
        for (Exporter exporter : EXPORTERS) {
            if (!formats.contains(exporter.id())) {
                continue;
            }
            try {
                Path file = exporter.export(source, paths);
                artifacts.add(new Artifact(exporter.manifestRole(), exporter.displayName(), file,
                        HashUtil.sha256(file), Files.size(file)));
                tracker.step("生成 " + exporter.displayName() + " " + file.getFileName());
                listener.onLog("已生成 " + exporter.displayName() + "：" + file.getFileName());
            } catch (Exception e) {
                failures.add(new Failure(exporter.manifestRole(), paths.fileName(exporter.extension()), describe(e)));
                tracker.step("生成 " + exporter.displayName() + " 失败：" + describe(e));
                listener.onLog("导出失败（" + exporter.displayName() + "）：" + describe(e));
            }
        }

        // README.txt（始终生成，描述包内文件清单）。
        try {
            Path readme = new ReadmeExporter().export(source, paths);
            artifacts.add(new Artifact("bundle-readme", "报告包说明", readme,
                    HashUtil.sha256(readme), Files.size(readme)));
            tracker.step("生成报告包说明 README.txt");
        } catch (Exception e) {
            failures.add(new Failure("bundle-readme", ReadmeExporter.README_FILE_NAME, describe(e)));
            tracker.step("生成 README.txt 失败：" + describe(e));
        }

        // manifest.json（始终生成，记录文件清单与真实 SHA-256）。
        try {
            Path manifest = ManifestWriter.write(source, paths, toEntries(artifacts), toManifestFailures(failures));
            artifacts.add(new Artifact("bundle-manifest", "报告包清单", manifest,
                    HashUtil.sha256(manifest), Files.size(manifest)));
            tracker.step("生成 manifest.json");
        } catch (Exception e) {
            failures.add(new Failure("bundle-manifest", ManifestWriter.FILE_NAME, describe(e)));
            tracker.step("生成 manifest.json 失败：" + describe(e));
        }

        // ZIP（仅在用户勾选时打包）。
        if (includeZip) {
            try {
                ZipPackager.Result result = ZipPackager.pack(paths, zipEntries(paths));
                artifacts.add(new Artifact("bundle-zip", "报告包 ZIP", result.zipFile(),
                        HashUtil.sha256(result.zipFile()), result.sizeBytes()));
                tracker.step("打包 ZIP " + paths.zipFileName());
                listener.onLog("已打包：" + result.zipFile());
            } catch (Exception e) {
                failures.add(new Failure("bundle-zip", paths.zipFileName(), describe(e)));
                tracker.step("ZIP 打包失败：" + describe(e));
            }
        }

        return finishBundle(paths, jsonFile, artifacts, failures, listener);
    }

    // ------------------------------------------------------------------ 内部实现

    /** 5 种报表 + README + manifest + ZIP，共 8 个步骤。 */
    private static void exportAll(Report source, ReportPaths paths, ProgressTracker tracker,
                                  EngineListener listener, boolean skipReports,
                                  List<Artifact> artifacts, List<Failure> failures) {
        for (Exporter exporter : EXPORTERS) {
            if (skipReports) {
                tracker.step("已跳过 " + exporter.displayName() + " 导出（--no-report 调试模式）");
                continue;
            }
            try {
                Path file = exporter.export(source, paths);
                String sha256 = HashUtil.sha256(file);
                long size = Files.size(file);
                artifacts.add(new Artifact(exporter.manifestRole(), exporter.displayName(), file, sha256, size));
                tracker.step("生成 " + exporter.displayName() + " " + file.getFileName() + "（" + TextUtil.humanSize(size) + "）");
                listener.onLog("已生成 " + exporter.displayName() + "：" + file.getFileName());
            } catch (Exception e) {
                failures.add(new Failure(exporter.manifestRole(), paths.fileName(exporter.extension()), describe(e)));
                tracker.step("生成 " + exporter.displayName() + " 失败：" + describe(e));
                listener.onLog("导出失败（" + exporter.displayName() + "）：" + describe(e));
            }
        }

        // README.txt
        if (skipReports) {
            tracker.step("已跳过报告包说明 README.txt（--no-report 调试模式）");
        } else {
            try {
                Path readme = new ReadmeExporter().export(source, paths);
                artifacts.add(new Artifact("bundle-readme", "报告包说明", readme,
                        HashUtil.sha256(readme), Files.size(readme)));
                tracker.step("生成报告包说明 README.txt（" + TextUtil.humanSize(Files.size(readme)) + "）");
            } catch (Exception e) {
                failures.add(new Failure("bundle-readme", ReadmeExporter.README_FILE_NAME, describe(e)));
                tracker.step("生成 README.txt 失败：" + describe(e));
                listener.onLog("导出失败（README.txt）：" + describe(e));
            }
        }

        // manifest.json（其中 sha256 全部为真实计算结果）
        if (skipReports) {
            tracker.step("已跳过 manifest.json（--no-report 调试模式）");
        } else {
            try {
                Path manifest = ManifestWriter.write(source, paths, toEntries(artifacts), toManifestFailures(failures));
                artifacts.add(new Artifact("bundle-manifest", "报告包清单", manifest,
                        HashUtil.sha256(manifest), Files.size(manifest)));
                tracker.step("生成 manifest.json（登记 " + artifacts.size() + " 个文件的真实 SHA-256）");
            } catch (Exception e) {
                failures.add(new Failure("bundle-manifest", ManifestWriter.FILE_NAME, describe(e)));
                tracker.step("生成 manifest.json 失败：" + describe(e));
                listener.onLog("导出失败（manifest.json）：" + describe(e));
            }
        }

        // ZIP 打包
        if (skipReports) {
            tracker.step("已跳过 ZIP 打包（--no-report 调试模式）");
        } else {
            try {
                ZipPackager.Result result = ZipPackager.pack(paths, zipEntries(paths));
                artifacts.add(new Artifact("bundle-zip", "报告包 ZIP", result.zipFile(),
                        HashUtil.sha256(result.zipFile()), result.sizeBytes()));
                if (result.hasSkipped()) {
                    failures.add(new Failure("bundle-zip", paths.zipFileName(),
                            "以下文件不存在，未打入 ZIP：" + TextUtil.join(result.skipped(), ", ")));
                }
                tracker.step("打包 ZIP " + paths.zipFileName() + "（" + TextUtil.humanSize(result.sizeBytes())
                        + "，" + result.packed().size() + " 个条目）");
                listener.onLog("已打包：" + result.zipFile());
            } catch (Exception e) {
                failures.add(new Failure("bundle-zip", paths.zipFileName(), describe(e)));
                tracker.step("ZIP 打包失败：" + describe(e));
                listener.onLog("ZIP 打包失败：" + describe(e));
            }
        }
    }

    private static Bundle finishBundle(ReportPaths paths, Path jsonFile,
                                       List<Artifact> artifacts, List<Failure> failures,
                                       EngineListener listener) {
        Path zip = null;
        for (Artifact artifact : artifacts) {
            if ("bundle-zip".equals(artifact.role())) {
                zip = artifact.path();
            }
        }
        boolean complete = failures.isEmpty();
        listener.onLog("报告生成完成：" + (complete ? "全部形态均已生成" : "存在导出失败项 " + failures.size() + " 项"));
        listener.onStatus(complete ? "报告生成完成" : "报告生成完成（部分形态失败）");
        Set<String> roles = new LinkedHashSet<>();
        for (Artifact artifact : artifacts) {
            roles.add(artifact.role());
        }
        listener.onLog("产出形态：" + String.join(", ", roles));
        return new Bundle(paths.reportId(), paths.directory(), jsonFile, zip, artifacts, failures, complete);
    }

    private static List<ManifestWriter.FileEntry> toEntries(List<Artifact> artifacts) {
        List<ManifestWriter.FileEntry> entries = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if ("bundle-manifest".equals(artifact.role())) {
                continue; // manifest 不登记自身哈希
            }
            entries.add(new ManifestWriter.FileEntry(
                    artifact.path().getFileName().toString(),
                    mimeOf(artifact.role()),
                    artifact.role(),
                    artifact.sha256(),
                    artifact.sizeBytes()));
        }
        return entries;
    }

    private static List<ManifestWriter.Failure> toManifestFailures(List<Failure> failures) {
        List<ManifestWriter.Failure> list = new ArrayList<>();
        for (Failure failure : failures) {
            list.add(new ManifestWriter.Failure(failure.role(), failure.displayName(), failure.message()));
        }
        return list;
    }

    private static List<String> zipEntries(ReportPaths paths) {
        List<String> names = new ArrayList<>();
        names.add(ReadmeExporter.README_FILE_NAME);
        names.add(ManifestWriter.FILE_NAME);
        names.add(paths.jsonFileName());
        names.add(paths.fileName("html"));
        names.add(paths.fileName("pdf"));
        names.add(paths.fileName("full", "pdf"));
        names.add(paths.fileName("md"));
        names.add(paths.fileName("txt"));
        names.add(paths.fileName("xlsx"));
        return names;
    }

    private static String mimeOf(String role) {
        for (Exporter exporter : EXPORTERS) {
            if (exporter.manifestRole().equals(role)) {
                return exporter.mimeType();
            }
        }
        return switch (role) {
            case "master-data-source" -> "application/json";
            case "bundle-readme" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private static Instant reportInstant(Report report) {
        String start = report.testMeta == null ? null : report.testMeta.testStartTime;
        if (TextUtil.isNotBlank(start)) {
            try {
                return Instant.parse(start);
            } catch (Exception ignored) {
                // 解析失败时退回当前时间，仍保证编号格式正确。
            }
        }
        return Instant.now();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (TextUtil.isBlank(message)) {
            message = error.getClass().getSimpleName();
        }
        return message;
    }

}
