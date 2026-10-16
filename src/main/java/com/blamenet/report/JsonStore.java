package com.blamenet.report;

import com.blamenet.model.Report;
import com.blamenet.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSON 主数据源读写。
 * <p>
 * 同源生成的关键实现点：采集流程先把内存报告写成 JSON，随后所有导出器都以
 * {@link #read(Path)} 反序列化出来的报告对象为唯一输入——导出层不可能再次采集数据，
 * 从根本上保证 HTML / PDF / MD / TXT / XLSX / README / manifest 完全同源。
 */
public final class JsonStore {

    private JsonStore() {
    }

    /** 写入主数据源 JSON。 */
    public static Path write(Report report, ReportPaths paths) throws IOException {
        Path file = paths.jsonFile();
        String json = JsonUtil.toPrettyJson(report);
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }

    /** 读取主数据源 JSON。 */
    public static Report read(Path jsonFile) throws IOException {
        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        return JsonUtil.fromJson(json, Report.class);
    }

    /** 读取主数据源 JSON 原文。 */
    public static String readRaw(Path jsonFile) throws IOException {
        return Files.readString(jsonFile, StandardCharsets.UTF_8);
    }
}
