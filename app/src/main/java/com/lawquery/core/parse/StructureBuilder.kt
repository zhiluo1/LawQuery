package com.lawquery.core.parse

import com.lawquery.domain.model.Chapter
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.Section

/**
 * 通用「章 → 节 → 条」结构化构建器(需求 F3 正文区)。
 * 由 GovCnParser / CourtParser 共用;无章条结构时降级为纯文本(项目文档 M4 解析容错)。
 */
object StructureBuilder {

    private const val NUMERAL_CLASS = "〇零一二三四五六七八九十百千0-9０-９"

    private val chapterRegex = Regex("^第[$NUMERAL_CLASS]+编|^第[$NUMERAL_CLASS]+章")
    private val sectionRegex = Regex("^第[$NUMERAL_CLASS]+节")
    private val articleRegex = Regex("^第[$NUMERAL_CLASS]+条")

    fun isChapterHeading(text: String) = chapterRegex.containsMatchIn(text)
    fun isSectionHeading(text: String) = sectionRegex.containsMatchIn(text)
    fun isArticleHeading(text: String) = articleRegex.containsMatchIn(text)

    /** 「第一百零七条」→ 107;「第12章」→ 12;无法解析返回 null */
    fun numeralAfterDi(text: String): Int? {
        val match = Regex("第([$NUMERAL_CLASS]+)[编章节条]").find(text) ?: return null
        return ChineseNumeral.parse(match.groupValues[1])
    }

    /**
     * 将段落文本块构建为 章/节/条 层级。
     * @param blocks 按文档顺序的文本块(段落/标题)
     * @return chapters 与前言;若无任何「条」结构,chapters 为空(纯文本降级)
     */
    fun build(blocks: List<String>): BuildResult {
        val preamble = mutableListOf<String>()
        val chapters = mutableListOf<MutableChapter>()

        var currentChapter: MutableChapter? = null
        var currentSection: MutableSection? = null
        var currentArticle: MutableArticle? = null
        var sawArticle = false

        fun closeArticle() {
            currentArticle?.let { article ->
                val c = currentChapter
                val s = currentSection
                when {
                    s != null -> s.articles += article.toArticle()
                    c != null -> c.looseArticles += article.toArticle()
                    // 条出现在任何章之前:放入一个无名章,保证不丢内容
                    else -> {
                        val ch = MutableChapter("")
                        ch.looseArticles += article.toArticle()
                        chapters += ch
                    }
                }
            }
            currentArticle = null
        }

        fun ensureChapter(title: String): MutableChapter {
            closeArticle()
            val key = normalize(title)
            // 目录与正文都会出现「第X章」标题(如公安部规章库 htmlContent 开头是目录页):
            // 若先前已存在同名且尚无任何内容的章(即目录产物),直接复用,让正文回填进去,
            // 否则会建出成倍的空章、条文看似没挂在章下。
            val ch = chapters.firstOrNull {
                normalize(it.title) == key && it.isEmpty
            } ?: MutableChapter(title).also { chapters += it }
            currentChapter = ch
            currentSection = null
            return ch
        }

        fun ensureSection(title: String): MutableSection {
            closeArticle()
            val ch = currentChapter ?: ensureChapter("")
            val key = normalize(title)
            // 同理:目录里的「第X节」复用正文的同名节
            val sec = ch.sections.firstOrNull {
                normalize(it.title) == key && it.articles.isEmpty()
            } ?: MutableSection(title).also { ch.sections += it }
            currentSection = sec
            return sec
        }

        for (raw in blocks) {
            val text = raw.trim()
            if (text.isEmpty()) continue

            when {
                chapterRegex.containsMatchIn(text) && text.length <= 30 -> {
                    ensureChapter(text)
                    sawArticle = sawArticle // 编/章不改变 sawArticle
                }
                sectionRegex.containsMatchIn(text) && text.length <= 30 -> {
                    ensureSection(text)
                }
                articleRegex.containsMatchIn(text) -> {
                    closeArticle()
                    sawArticle = true
                    val number = articleRegex.find(text)?.value ?: ""
                    val rest = text.removePrefix(number).trim { it == '　' || it.isWhitespace() }
                    currentArticle = MutableArticle(number)
                    if (rest.isNotEmpty()) currentArticle!!.paragraphs += rest
                }
                else -> {
                    val article = currentArticle
                    when {
                        article != null -> article.paragraphs += text
                        currentSection != null -> {
                            // 节下、条前的说明文字并入前言
                            preamble += text
                        }
                        else -> preamble += text
                    }
                }
            }
        }
        closeArticle()

        val resultChapters = chapters.map { it.toChapter() }
        return BuildResult(
            preamble = preamble.toList(),
            chapters = resultChapters,
            hasStructure = sawArticle && resultChapters.isNotEmpty() &&
                resultChapters.any { it.articles.isNotEmpty() },
        )
    }

    data class BuildResult(
        val preamble: List<String>,
        val chapters: List<Chapter>,
        val hasStructure: Boolean,
    )

    /** 标题归一化:去掉全部空白(含全角空格),供目录/正文同名标题匹配 */
    private fun normalize(text: String): String = buildString(text.length) {
        for (ch in text) if (!ch.isWhitespace()) append(ch)
    }

    private class MutableArticle(val number: String) {
        val paragraphs = mutableListOf<String>()
        fun toArticle() = LawArticle(
            number = number,
            arabicNumber = ChineseNumeral.parse(number.removePrefix("第").removeSuffix("条")),
            paragraphs = paragraphs.toList(),
        )
    }

    private class MutableSection(val title: String) {
        val articles = mutableListOf<LawArticle>()
        fun toSection() = Section(title, articles.toList())
    }

    private class MutableChapter(val title: String) {
        val sections = mutableListOf<MutableSection>()
        val looseArticles = mutableListOf<LawArticle>()

        /** 还没有挂进任何条文(目录产物的特征;目录可能同时带出空「节」,也算空章) */
        val isEmpty: Boolean
            get() = looseArticles.isEmpty() && sections.all { it.articles.isEmpty() }

        fun toChapter(): Chapter {
            val secs = sections.map { it.toSection() }.toMutableList()
            if (looseArticles.isNotEmpty()) {
                secs.add(0, Section("", looseArticles.toList()))
            }
            return Chapter(
                title = title,
                arabicNumber = ChineseNumeral.parse(title.removePrefix("第").takeWhile { it != '章' && it != '编' && it != '节' }),
                sections = secs,
            )
        }
    }
}

/** 中文数字 → 阿拉伯数字(支持 一~九千九百九十九 与阿拉伯数字混排) */
object ChineseNumeral {
    private val digits = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000)

    fun parse(input: String?): Int? {
        if (input.isNullOrBlank()) return null
        val normalized = input.trim()
            .map { if (it in '０'..'９') ('0' + (it - '０')) else it }
            .joinToString("")
        if (normalized.all { it in '0'..'9' }) return normalized.toIntOrNull()
        if (normalized.any { it in '0'..'9' }) {
            // 混排(罕见):取阿拉伯部分
            val m = Regex("\\d+").find(normalized)
            return m?.value?.toIntOrNull()
        }

        var result = 0
        var current = 0
        for (ch in normalized) {
            when {
                digits.containsKey(ch) -> current = digits[ch]!!
                units.containsKey(ch) -> {
                    val unit = units[ch]!!
                    result += (if (current == 0) 1 else current) * unit
                    current = 0
                }
                else -> return null
            }
        }
        val total = result + current
        return if (total > 0) total else null
    }
}
