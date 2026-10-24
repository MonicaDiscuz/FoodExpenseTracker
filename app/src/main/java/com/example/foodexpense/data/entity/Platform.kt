package com.example.foodexpense.data.entity

/**
 * 外卖平台枚举。
 * 存储到数据库时使用 [name]（英文标识），展示与导入时使用 [displayName]（中文名）。
 */
enum class Platform(val displayName: String) {
    MEITUAN("美团"),
    TAOBAO_FLASH("淘宝闪购"),
    JD("京东");

    companion object {
        /** 按存储名（枚举 name）查找，找不到返回 null。 */
        fun fromName(name: String?): Platform? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** 兼容中文名 / 英文名的宽松解析，用于 CSV 导入。 */
        fun fromDisplayName(name: String?): Platform? {
            val n = name?.trim().orEmpty()
            if (n.isEmpty()) return null
            return when {
                n.contains("美团") || n.equals("meituan", ignoreCase = true) -> MEITUAN
                n.contains("闪购") || n.contains("淘宝") ||
                    n.contains("taobao", ignoreCase = true) -> TAOBAO_FLASH
                n.contains("京东") || n.contains("jd", ignoreCase = true) ||
                    n.contains("jingdong", ignoreCase = true) -> JD
                else -> fromName(n)
            }
        }
    }
}
