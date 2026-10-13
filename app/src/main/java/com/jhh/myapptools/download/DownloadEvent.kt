package com.jhh.myapptools.download

import java.io.File

/** 下载/解析过程中的事件（解析器与下载器共用） */
sealed interface DownloadEvent {
    data class Stage(val text: String) : DownloadEvent
    data class Log(val line: String) : DownloadEvent
    data class Progress(val fraction: Float, val line: String) : DownloadEvent
    data class Downloaded(val file: File) : DownloadEvent
    data class Failure(val message: String, val diagnostics: String?) : DownloadEvent
    data object Cancelled : DownloadEvent
}
