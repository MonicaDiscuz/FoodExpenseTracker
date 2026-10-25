package com.example.foodexpense.util

import com.example.foodexpense.data.entity.MealRecordEntity

/**
 * 覆盖导入的**执行顺序保证**：先写库，成功后才删旧图片。
 *
 * ## 这里曾经有一个会造成永久数据丢失的 bug
 * 原实现是**先删旧图片、再写库**：
 *
 * ```kotlin
 * // ❌ 旧的错误顺序
 * getAllRecords().flatMap { it.reviewImages }.forEach { deleteFile(it) }  // 图片先没了
 * replaceAll(result.records)                                             // 这行一失败，图片无法恢复
 * ```
 *
 * `replaceAll` 内部虽然有事务，但**图片删除发生在事务之前**。于是只要这一步失败
 * （数据库异常、磁盘满、进程被系统杀掉、用户切走），就会停在这样一个状态：
 *
 * - 数据库里还是**旧记录**
 * - 旧记录引用的图片文件**已经被删除**
 *
 * 也就是说，界面上记录都还在、点开评价却全是裂图，而且**无法恢复**。
 * 覆盖导入是个低频但高危的操作（用户往往是在换手机时用），
 * 一次失败就等于把历史评价图片全部丢掉。
 *
 * ## 现在的顺序
 * 1. 先只**读取**旧路径，不动文件；
 * 2. 写库；抛异常就直接向上传播，**此时一个图片文件都还没动过**，数据完全没风险；
 * 3. 写库成功后才删「旧有新没有」的差集。
 *
 * 第 3 步为什么用差集而不是全删：备份里的图片路径理论上都是新落盘的，
 * 但那只是 `ImageStorage.saveCompressed` 的当前实现细节。万一将来它复用了已有路径，
 * 全删就会把**刚导入的记录**指向的图片删掉。差集是零成本的保险。
 *
 * ## 为什么抽成这个形状
 * 这条不变量是"顺序"，光靠注释守不住 —— 后来的人把两行换个位置，代码看着一样合理。
 * 把「读旧路径 / 写库 / 删文件」三个动作做成注入参数，
 * 就能用一个纯 JVM 测试直接证明「写库失败时 delete 一次都没被调用」，
 * 见 `SafeReplaceTest`。反过来把顺序调换，那个用例立刻变红。
 *
 * @param newRecords 准备写入的新记录（来自备份）
 * @param loadOldImagePaths 读取当前库里所有评价图片路径（只读，不得有副作用）
 * @param writeRecords 真正写库；允许抛异常
 * @param deleteFile 删除单个图片文件（实现里应自己吞掉 IO 异常）
 */
internal suspend fun replaceRecordsThenCleanupImages(
    newRecords: List<MealRecordEntity>,
    loadOldImagePaths: suspend () -> List<String>,
    writeRecords: suspend (List<MealRecordEntity>) -> Unit,
    deleteFile: (String) -> Unit
) {
    val oldPaths = loadOldImagePaths()

    // 顺序关键：必须先写库。这里抛异常就直接向上冒泡，旧图片一张都没删。
    writeRecords(newRecords)

    // 走到这里说明库已经写成功了，旧图片才算真正"不再被引用"
    val stillReferenced = newRecords.flatMap { it.reviewImages }.toSet()
    (oldPaths.toSet() - stillReferenced).forEach(deleteFile)
}
