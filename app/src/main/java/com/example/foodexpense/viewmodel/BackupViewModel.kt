package com.example.foodexpense.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.foodexpense.FoodExpenseApp
import com.example.foodexpense.util.DataBackup
import com.example.foodexpense.util.ImageStorage
import com.example.foodexpense.util.replaceRecordsThenCleanupImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一键导出 / 导入（含评价图片）。 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = getApplication<FoodExpenseApp>().recordRepository
    private val app = getApplication<FoodExpenseApp>()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    /** 导出到用户在系统文件选择器里选定的文件。 */
    fun export(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        try {
            val records = repository.getAllRecords()
            if (records.isEmpty()) {
                _messages.emit("没有可导出的记录")
                return@launch
            }
            withContext(Dispatchers.IO) {
                val out = app.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法写入所选文件")
                out.use { DataBackup.export(records, it) }
            }
            _messages.emit("已导出 ${records.size} 条记录（含评价与图片）")
        } catch (e: Exception) {
            _messages.emit("导出失败：${e.message ?: "未知错误"}")
        } finally {
            _busy.value = false
        }
    }

    /**
     * 从备份文件导入。
     * @param replace true = 覆盖（先清空本机数据，结果与备份完全一致）；false = 追加合并
     */
    fun import(uri: Uri, replace: Boolean) = viewModelScope.launch {
        _busy.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                input.use { DataBackup.import(app, it) }
            }
            if (result.records.isEmpty()) {
                _messages.emit("备份文件里没有可导入的记录")
                return@launch
            }
            if (replace) {
                // 覆盖导入：**先写库，成功后才删旧图片**。
                // 顺序绝不能反过来——详见 [replaceRecordsThenCleanupImages] 的注释，
                // 那里解释了"先删图再写库"会在写库失败时造成评价图片永久丢失。
                withContext(Dispatchers.IO) {
                    replaceRecordsThenCleanupImages(
                        newRecords = result.records,
                        loadOldImagePaths = {
                            repository.getAllRecords().flatMap { it.reviewImages }
                        },
                        writeRecords = { repository.replaceAll(it) },
                        deleteFile = { ImageStorage.deleteFile(it) }
                    )
                }
            } else {
                repository.appendAll(result.records)
            }
            _messages.emit(
                "已导入 ${result.records.size} 条记录" +
                    if (result.imageCount > 0) "（含 ${result.imageCount} 张图片）" else ""
            )
        } catch (e: Exception) {
            _messages.emit("导入失败：${e.message ?: "未知错误"}")
        } finally {
            _busy.value = false
        }
    }

    /** 批量压缩已保存的评价图片（相机原图动辄 3~4MB，压到 200~400KB）。 */
    fun compressExistingImages() = viewModelScope.launch {
        _busy.value = true
        try {
            val paths = repository.getAllRecords()
                .flatMap { it.reviewImages }
                .distinct()
            if (paths.isEmpty()) {
                _messages.emit("没有评价图片可压缩")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                var count = 0
                var saved = 0L
                paths.forEach { path ->
                    val delta = ImageStorage.compressInPlace(path)
                    if (delta > 0) {
                        count++
                        saved += delta
                    }
                }
                count to saved
            }
            _messages.emit(
                if (result.first == 0) {
                    "没有需要压缩的大图（都已足够小）"
                } else {
                    "已压缩 ${result.first} 张图片，节省 ${formatSize(result.second)}"
                }
            )
        } catch (e: Exception) {
            _messages.emit("压缩失败：${e.message ?: "未知错误"}")
        } finally {
            _busy.value = false
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) {
            "%.1f MB".format(bytes / 1024.0 / 1024.0)
        } else {
            "${bytes / 1024} KB"
        }
}
