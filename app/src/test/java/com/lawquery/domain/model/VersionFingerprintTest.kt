package com.lawquery.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本指纹比对单测(需求 6.2 / 验收 D3:发文字号、公布日期、施行日期、时效性任一变化即判更新)。
 */
class VersionFingerprintTest {

    private fun fp(
        docNumber: String? = "国令第790号",
        publish: String? = "2024-09-30",
        effective: String? = "2025-01-01",
        status: LawStatus = LawStatus.CURRENT,
    ) = VersionFingerprint(
        docNumber = docNumber,
        publishDate = publish?.let(LocalDate::parse),
        effectiveDate = effective?.let(LocalDate::parse),
        status = status,
    )

    @Test
    fun `指纹一致视为未更新`() {
        assertEquals(fp().normalized(), fp().normalized())
    }

    @Test
    fun `公布日期变化判定更新(验收D3)`() {
        assertNotEquals(fp().normalized(), fp(publish = "2024-10-01").normalized())
    }

    @Test
    fun `时效性变化判定更新`() {
        assertNotEquals(fp().normalized(), fp(status = LawStatus.REPEALED).normalized())
    }

    @Test
    fun `发文字号变化判定更新`() {
        assertNotEquals(fp().normalized(), fp(docNumber = "国令第791号").normalized())
    }

    @Test
    fun `空白与null等价避免误报`() {
        assertEquals(fp(docNumber = null).normalized(), fp(docNumber = "").normalized())
    }

    @Test
    fun `normalized不修改原对象`() {
        val original = fp()
        original.normalized()
        assertEquals("国令第790号", original.docNumber)
        assertTrue(original.publishDate != null)
    }
}
