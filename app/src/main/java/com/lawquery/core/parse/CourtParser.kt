package com.lawquery.core.parse

import java.time.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 最高人民法院官网解析器(项目文档 core/parse,无 IO)。
 *
 * M0 实测结构:
 * - 栏目列表:/fabu/gengduo/{channel}.html(第 N 页为 {channel}_{N}.html)
 *   条目:<a href="/fabu/xiangqing/{id}.html">标题</a>,日期在条目附近(yyyy-MM-dd)
 * - 详情页:div.detail > div.title(标题)、div.detail_mes li(来源：/发布时间：)、
 *   正文 div.txt_txt > <p>
 */
object CourtParser {

    data class ListItem(
        val id: String,
        val title: String,
        val url: String,
        val date: LocalDate?,
    )

    data class ParsedList(
        val items: List<ListItem>,
        val nextPageUrl: String?,
    )

    data class ParsedDetail(
        val title: String?,
        val sourceName: String?,
        val publishDate: LocalDate?,
        val structure: StructureBuilder.BuildResult,
    )

    fun parseList(html: String, baseUrl: String): ParsedList {
        val doc = Jsoup.parse(html, baseUrl)
        val items = mutableListOf<ListItem>()
        val seen = mutableSetOf<String>()

        for (a in doc.select("a[href*=xiangqing]")) {
            val href = a.attr("abs:href")
            val id = Regex("xiangqing/(\\d+)\\.html").find(a.attr("href"))?.groupValues?.get(1) ?: continue
            val title = a.text().trim()
            if (title.length < 4 || !seen.add(id)) continue
            val date = findDateNear(a)
            items += ListItem(id = id, title = title, url = href, date = date)
        }

        val next = doc.selectFirst("a:contains(下一页)")?.attr("abs:href")
        return ParsedList(items = items, nextPageUrl = next?.takeIf { it.isNotBlank() })
    }

    private fun findDateNear(anchor: org.jsoup.nodes.Element): LocalDate? {
        var node: Element? = anchor
        var hops = 0
        while (node != null && hops < 4) {
            val m = Regex("(\\d{4}-\\d{2}-\\d{2})").find(node.text())
            if (m != null) {
                return GovCnParser.parseDate(m.value)
            }
            node = node.parent()
            hops++
        }
        return null
    }

    fun parseDetail(html: String, pageUrl: String): ParsedDetail {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst(".detail .title")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }

        var sourceName: String? = null
        var publishDate: LocalDate? = null
        for (li in doc.select(".detail_mes li, .message li")) {
            val text = li.text()
            if (text.startsWith("来源：") || text.startsWith("来源:")) {
                sourceName = text.substringAfter(if (text[2] == '：') "来源：" else "来源:").trim()
            }
            val dm = Regex("(\\d{4}-\\d{2}-\\d{2})").find(text)
            if (dm != null && publishDate == null) publishDate = GovCnParser.parseDate(dm.value)
        }

        val container = doc.selectFirst(".txt_txt")
            ?: doc.selectFirst("div.txt")
            ?: doc.selectFirst(".detail")
        val blocks = mutableListOf<String>()
        if (container != null) {
            for (el in container.select("p")) {
                val text = el.wholeText().replace('\u00a0', ' ').replace("　", " ").trim()
                if (text.isNotEmpty()) blocks += text
            }
            if (blocks.isEmpty()) {
                val whole = container.wholeText().replace('\u00a0', ' ')
                blocks += whole.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        return ParsedDetail(
            title = title,
            sourceName = sourceName,
            publishDate = publishDate,
            structure = StructureBuilder.build(blocks),
        )
    }
}
