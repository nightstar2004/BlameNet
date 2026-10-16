package com.blamenet.core;

import com.blamenet.model.ModuleResult;

/** 诊断引擎事件监听：界面与命令行共同实现该接口。 */
public interface EngineListener {

    /** 一般日志（界面滚动区 / 命令行日志）。 */
    void onLog(String message);

    /** 开始执行某个模块。 */
    void onModuleStarted(CheckModule module, int index, int total);

    /** 某个模块执行完成。 */
    void onModuleFinished(ModuleResult result);

    /** 进度变化。 */
    void onProgress(ProgressEvent event);

    /** 整体状态变化（例如“诊断完成，正在生成报告”）。 */
    void onStatus(String status);

    /** 是否已被用户请求中止。 */
    default boolean abortRequested() {
        return false;
    }
}
