package com.blamenet.report;

import com.blamenet.util.InstallPaths;
import com.blamenet.util.TimeUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 报告文件路径规划。
 * <p>
 * 设计要求：结果只写在安装目录下的 results 目录，每次诊断在该目录下创建
 * 「BlameNet-yyyy-MM-dd-HH-mm-ss报告」子文件夹（报告编号与文件夹名前缀一致），
 * 所有形态（json/html/pdf/md/txt/xlsx）共用同一个基名，保证一眼可对应同一份测试快照。
 */
public final class ReportPaths {

    private final Path directory;
    private final String reportId;

    private ReportPaths(Path directory, String reportId) {
        this.directory = directory;
        this.reportId = reportId;
    }

    public static ReportPaths create(Path resultsDirectory, Instant instant) throws IOException {
        return create(resultsDirectory, instant, "报告");
    }

    /**
     * 在指定父目录下创建以「报告编号 + 后缀」命名的子文件夹，报告文件全部写入该子文件夹，
     * 避免文件直接散落在 results / 目标目录中。存储时后缀为「报告」，导出时后缀为「报告导出」。
     */
    public static ReportPaths create(Path resultsDirectory, Instant instant, String folderSuffix) throws IOException {
        String reportId = "BlameNet-" + TimeUtil.stamp(instant);
        String folderName = reportId + (folderSuffix == null || folderSuffix.isEmpty() ? "" : folderSuffix);
        Path dir = InstallPaths.ensureDir(resultsDirectory.resolve(folderName));
        return new ReportPaths(dir, reportId);
    }

    /**
     * 从已有 JSON 主数据源定位报告目录与编号（用于“仅重新生成报表”模式）。
     * 目录取该 JSON 所在目录，编号取去掉扩展名的文件名，保证不会写错位置。
     */
    public static ReportPaths forExisting(Path jsonFile) throws IOException {
        Path absolute = jsonFile.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        if (dir == null) {
            throw new IOException("无法确定 JSON 主数据源所在目录：" + jsonFile);
        }
        String name = absolute.getFileName().toString();
        if (name.toLowerCase().endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        return new ReportPaths(dir, name);
    }

    public Path directory() {
        return directory;
    }

    public String reportId() {
        return reportId;
    }

    public String jsonFileName() {
        return reportId + ".json";
    }

    public String fileName(String extension) {
        return reportId + "." + extension;
    }

    /** 带后缀标识的文件名，例如 {@code fileName("full", "pdf")} → {@code BlameNet-<编号>-full.pdf}。 */
    public String fileName(String suffix, String extension) {
        return reportId + (suffix == null || suffix.isEmpty() ? "" : "-" + suffix) + "." + extension;
    }

    public Path jsonFile() {
        return directory.resolve(jsonFileName());
    }

    public Path file(String extension) {
        return directory.resolve(fileName(extension));
    }

    public Path file(String suffix, String extension) {
        return directory.resolve(fileName(suffix, extension));
    }

    public String zipFileName() {
        return reportId + "-bundle.zip";
    }

    public Path zipFile() {
        return directory.resolve(zipFileName());
    }

    /** ZIP 内部的文件夹名。 */
    public String bundleFolderName() {
        return reportId;
    }
}
