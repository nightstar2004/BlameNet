package com.blamenet.report;

import com.blamenet.model.Report;

import java.nio.file.Path;

/** 报表导出器契约。所有实现只能依赖 JSON 主数据源反序列化出的 {@link Report}。 */
public interface Exporter {

    /** 导出器标识，同时作为文件扩展名。 */
    String id();

    /** 展示名称（进度条上显示）。 */
    String displayName();

    /** 文件扩展名。 */
    String extension();

    /** manifest.json 中的角色标识。 */
    String manifestRole();

    /** MIME 类型。 */
    String mimeType();

    /** 执行导出，返回生成的文件路径。 */
    Path export(Report report, ReportPaths paths) throws Exception;
}
