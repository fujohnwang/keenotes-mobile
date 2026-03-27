package cn.keevol.keenotes.util

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 日期时间工具类
 * 统一处理日期时间的获取、转换和格式化
 *
 * 规范：
 * 1. 获取当前系统时区的本地时间
 * 2. 转换为 UTC
 * 3. 格式化为 yyyy-MM-dd HH:mm:ss
 */
object DateTimeUtil {

    private val TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 获取当前时间的 UTC 格式字符串
     * 流程：本地时区时间 -> 转为 UTC -> 格式化为标准格式
     *
     * @return 格式化后的 UTC 时间字符串 (yyyy-MM-dd HH:mm:ss)
     */
    fun getCurrentUtcTimestamp(): String {
        // 1. 获取当前系统时区的本地时间
        val localNow = LocalDateTime.now()
        // 2. 转为带系统时区信息
        val offsetNow = localNow.atZone(ZoneId.systemDefault()).toOffsetDateTime()
        // 3. 转为 UTC
        val utcNow = offsetNow.withOffsetSameInstant(ZoneOffset.UTC)
        // 4. 格式化为标准格式
        return utcNow.format(TS_FORMATTER)
    }

    /**
     * 将 UTC 时间字符串转换为本地时区显示字符串
     * 用于 UI 显示：数据库中存储的是 UTC，显示时转为本地时区
     *
     * @param utcString UTC 时间字符串 (yyyy-MM-dd HH:mm:ss)
     * @return 本地时区格式字符串，解析失败返回原字符串
     */
    fun utcToLocalDisplay(utcString: String?): String {
        if (utcString.isNullOrBlank()) {
            return utcString ?: ""
        }

        return try {
            // 解析 UTC 时间
            val utcDateTime = LocalDateTime.parse(utcString.take(19), TS_FORMATTER)
            // 转为带 UTC 时区的 OffsetDateTime
            val utc = utcDateTime.atOffset(ZoneOffset.UTC)
            // 转为系统本地时区
            val local = utc.withOffsetSameInstant(ZoneId.systemDefault().rules.getOffset(utc.toInstant()))
            // 格式化为显示格式
            local.format(TS_FORMATTER)
        } catch (e: Exception) {
            // 解析失败，返回原字符串
            utcString
        }
    }
}
