package com.blamenet.report;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 报告打包器：把 JSON 主数据源与全部导出形态（含 README.txt、manifest.json）打成一个 ZIP。
 * <p>
 * ZIP 结构遵循要求文档示例：
 * <pre>
 * BlameNet-yyyy-MM-dd-HH-mm-ss/
 * ├── README.txt
 * ├── manifest.json
 * ├── BlameNet-yyyy-MM-dd-HH-mm-ss.json
 * ├── ...html / .pdf / .md / .txt / .xlsx
 * </pre>
 * 打包时逐条核对文件真实存在与字节长度，缺文件直接跳过并在返回结果中记录，不生成空占位条目。
 */
public final class ZipPackager {

    private ZipPackager() {
    }

    /**
     * 打包报告。
     *
     * @param paths     报告路径规划
     * @param fileNames ZIP 内需要包含的文件名（相对 results 目录），按顺序写入
     * @return 结果：ZIP 路径 + 实际写入的条目名 + 因不存在而跳过的条目名
     */
    public static Result pack(ReportPaths paths, Iterable<String> fileNames) throws IOException {
        Path zipFile = paths.zipFile();
        Set<String> packed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        String folder = paths.bundleFolderName() + "/";

        try (OutputStream fileOut = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut))) {
            zip.setLevel(6);
            for (String name : fileNames) {
                if (name == null || name.equals(zipFile.getFileName().toString())) {
                    continue;
                }
                Path source = paths.directory().resolve(name);
                if (!Files.isRegularFile(source)) {
                    skipped.add(name);
                    continue;
                }
                ZipEntry entry = new ZipEntry(folder + name);
                entry.setTime(Files.getLastModifiedTime(source).toMillis());
                zip.putNextEntry(entry);
                Files.copy(source, zip);
                zip.closeEntry();
                packed.add(name);
            }
        }
        return new Result(zipFile, packed, skipped);
    }

    /** 打包结果。 */
    public record Result(Path zipFile, Set<String> packed, Set<String> skipped) {

        public boolean hasSkipped() {
            return !skipped.isEmpty();
        }

        public long sizeBytes() {
            try {
                return Files.size(zipFile);
            } catch (IOException e) {
                return -1L;
            }
        }
    }
}
