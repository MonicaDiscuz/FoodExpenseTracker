package com.example.foodexpense.util

import com.example.foodexpense.data.entity.MealRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [replaceRecordsThenCleanupImages] 的**顺序不变量**测试。
 *
 * ## 这个文件守的是什么
 * 覆盖导入曾经是「先删旧图片 → 再写库」。写库一旦失败（DB 异常 / 磁盘满 / 进程被杀），
 * 就停在"库里有旧记录、旧图片已被删"的状态，评价图片**永久丢失且无法恢复**。
 *
 * 这类 bug 的特点是：**光看代码很合理**，两行换个位置谁都看不出来；
 * 而真实触发需要"恰好写库失败"，几乎不可能手工复现。
 * 所以只能靠测试把「写库失败 → delete 一次都不能被调用」这条顺序钉死。
 *
 * 反向验证：把 `SafeReplace.kt` 里的 `writeRecords(...)` 挪到
 * `forEach(deleteFile)` 之后，本例立刻变红。
 */
class SafeReplaceTest {

    private fun record(images: List<String>) = MealRecordEntity(
        platform = "MEITUAN",
        orderTime = 1_700_000_000_000L,
        storeName = "店",
        productName = "商品",
        productPrice = 10.0,
        productQuantity = 1,
        reviewImages = images
    )

    /**
     * 核心用例：写库抛异常时，**一个文件都不许删**，异常照常向上抛。
     */
    @Test
    fun `写库失败时一个图片都不删且异常向上抛`() = runBlocking {
        val deleted = mutableListOf<String>()
        var caught: Throwable? = null

        try {
            replaceRecordsThenCleanupImages(
                newRecords = listOf(record(listOf("new/1.jpg"))),
                loadOldImagePaths = { listOf("old/1.jpg", "old/2.jpg") },
                writeRecords = { throw IllegalStateException("模拟数据库写失败") },
                deleteFile = { deleted += it }
            )
        } catch (e: Throwable) {
            caught = e
        }

        assertTrue("写库异常必须向上传播，不能在这里被吞掉", caught is IllegalStateException)
        assertTrue(
            "写库失败时绝不能删任何图片——否则旧记录会指向已删除的文件（P1 永久丢失）",
            deleted.isEmpty()
        )
    }

    /** 正常路径：只删「旧有新没有」的那些。 */
    @Test
    fun `写库成功后只删不再被引用的旧图片`() = runBlocking {
        val deleted = mutableListOf<String>()
        var written: List<MealRecordEntity>? = null

        replaceRecordsThenCleanupImages(
            newRecords = listOf(record(listOf("new/1.jpg")), record(listOf("new/2.jpg"))),
            loadOldImagePaths = { listOf("old/1.jpg", "old/2.jpg") },
            writeRecords = { written = it },
            deleteFile = { deleted += it }
        )

        assertEquals("新记录必须原样交给 writeRecords", 2, written?.size)
        assertEquals(listOf("old/1.jpg", "old/2.jpg"), deleted.sorted())
        assertTrue("新导入的图片绝不能被删", deleted.none { it.startsWith("new/") })
    }

    /**
     * 新旧路径有交集时，交集必须**保留**。
     *
     * 现在的实现里备份图片都会重新落盘成新文件名，理论上不会重名；
     * 但"全删旧路径"依赖的是 `ImageStorage` 的实现细节，一旦它改成复用路径，
     * 就会把刚导入的记录指向的图片删掉。差集是零成本的保险。
     */
    @Test
    fun `新旧路径有交集时交集保留`() = runBlocking {
        val deleted = mutableListOf<String>()

        replaceRecordsThenCleanupImages(
            newRecords = listOf(record(listOf("shared/a.jpg")), record(listOf("new/9.jpg"))),
            loadOldImagePaths = { listOf("shared/a.jpg", "old/b.jpg") },
            writeRecords = { },
            deleteFile = { deleted += it }
        )

        assertEquals(listOf("old/b.jpg"), deleted)
    }

    /** 本机原本没有任何图片 → 一次 delete 都不该发生。 */
    @Test
    fun `没有旧图片时不调用删除`() = runBlocking {
        var deleteCalls = 0

        replaceRecordsThenCleanupImages(
            newRecords = listOf(record(emptyList())),
            loadOldImagePaths = { emptyList() },
            writeRecords = { },
            deleteFile = { deleteCalls++ }
        )

        assertEquals(0, deleteCalls)
    }

    /** 同一路径在旧记录里出现多次（多条记录共用一张图）时，只删一次。 */
    @Test
    fun `旧路径重复时只删一次`() = runBlocking {
        val deleted = mutableListOf<String>()

        replaceRecordsThenCleanupImages(
            newRecords = emptyList(),
            loadOldImagePaths = { listOf("dup.jpg", "dup.jpg", "dup.jpg") },
            writeRecords = { },
            deleteFile = { deleted += it }
        )

        assertEquals(listOf("dup.jpg"), deleted)
    }

    /** 先读旧路径、再写库：读取必须发生在写入之前（否则读到的已经是新库）。 */
    @Test
    fun `读取旧路径发生在写库之前`() = runBlocking {
        val order = mutableListOf<String>()

        replaceRecordsThenCleanupImages(
            newRecords = emptyList(),
            loadOldImagePaths = { order += "read"; emptyList() },
            writeRecords = { order += "write" },
            deleteFile = { }
        )

        assertEquals(listOf("read", "write"), order)
    }
}
