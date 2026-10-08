package com.lawquery.core.parse

import java.time.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 中国政府网(政策文件库)解析器(项目文档 core/parse):
 * 只做 HTML → 模型,无 IO;改版只需修改本类(M0 探查结论见《数据源接入说明》)。
 *
 * 详情页结构(M0 实测):
 * - 元信息:table.bd1 内 <td><b>键：</b></td><td>值</td>
 *   键:索 引 号 / 主题分类 / 发文机关 / 成文日期 / 标　　题 / 发文字号 / 发布日期
 * - 正文:#UCAP-CONTENT > div.trs_editor_view > <p>
 */
object GovCnParser {

    private val META_KEYS = mapOf(
        "索引号" to "index",
        "主题分类" to "subject",
        "发文机关" to "authority",
        "成文日期" to "writtenDate",
        "标题" to "title",
        "发文字号" to "docNumber",
        "发布日期" to "publishDate",
    )

    data class ParsedDetail(
        val title: String?,
        val authority: String?,
        val docNumber: String?,
        val publishDate: LocalDate?,
        val effectiveDate: LocalDate?,
        val structure: StructureBuilder.BuildResult,
    )

    fun parseDetail(html: String, pageUrl: String): ParsedDetail {
        val doc = Jsoup.parse(html, pageUrl)
        val meta = extractMeta(doc)
        val blocks = extractContentBlocks(doc)
        val structure = StructureBuilder.build(blocks)

        val title = meta["title"]
            ?: doc.selectFirst("h1")?.text()
            ?: doc.title().takeIf { it.isNotBlank() }

        return ParsedDetail(
            title = title?.trim(),
            authority = meta["authority"]?.trim(),
            docNumber = meta["docNumber"]?.trim(),
            publishDate = meta["publishDate"]?.let(::parseDate) ?: meta["writtenDate"]?.let(::parseDate),
            effectiveDate = structure.effectiveDateFromText(),
            structure = structure,
        )
    }

    /** 元信息表格(table.bd1)容忍版:扫描全部 <td><b>键：</b></td><td>值</td> 组合 */
    internal fun extractMeta(doc: Document): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val tds = doc.select("td")
        var i = 0
        while (i < tds.size - 1) {
            val cell = tds[i]
            val b = cell.selectFirst("b")
            if (b != null) {
                val key = normalizeKey(b.text())
                val mapped = META_KEYS[key]
                if (mapped != null) {
                    val valueCell = tds[i + 1]
                    if (!valueCell.text().isBlank()) {
                        result[mapped] = valueCell.text().trim()
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        return result
    }

    private fun normalizeKey(raw: String): String =
        raw.replace("：", "").replace(":", "").replace("　", "").replace(" ", "").trim()

    /** 正文块:优先 #UCAP-CONTENT,失败降级到常见容器,再失败取 body 全文 */
    internal fun extractContentBlocks(doc: Document): List<String> {
        val container = doc.selectFirst("#UCAP-CONTENT")
            ?: doc.selectFirst("div.trs_editor_view")
            ?: doc.selectFirst(".pages_content")
            ?: doc.selectFirst("#articleContent")
            ?: doc.body()
        val blocks = mutableListOf<String>()
        if (container != null) {
            val nodes = container.select("p, h1, h2, h3, h4, h5")
            for (el in nodes) {
                val text = normalize(el)
                if (text.isNotEmpty()) blocks += text
            }
            if (blocks.isEmpty()) {
                // 极端情况:正文不以 <p> 组织,整块取文本
                val whole = normalize(container)
                if (whole.isNotEmpty()) blocks += whole.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return blocks
    }

    private fun normalize(el: Element): String =
        el.wholeText().replace('\u00a0', ' ').replace("　", " ")
            .lines().joinToString(" ") { it.trim() }.trim()

    private fun StructureBuilder.BuildResult.effectiveDateFromText(): LocalDate? {
        val text = (preamble + chapters.flatMap { c -> c.articles.flatMap { it.paragraphs } })
            .joinToString("")
        val m = Regex("自(\\d{4})年(\\d{1,2})月(\\d{1,2})日起施行").find(text) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }

    /** 支持 2026年08月08日 / 2026-08-08 / 2026.08.08 */
    fun parseDate(raw: String): LocalDate? {
        val cn = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日").find(raw)
        if (cn != null) {
            return runCatching {
                LocalDate.of(cn.groupValues[1].toInt(), cn.groupValues[2].toInt(), cn.groupValues[3].toInt())
            }.getOrNull()
        }
        val std = Regex("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})").find(raw) ?: return null
        return runCatching {
            LocalDate.of(std.groupValues[1].toInt(), std.groupValues[2].toInt(), std.groupValues[3].toInt())
        }.getOrNull()
    }
}
