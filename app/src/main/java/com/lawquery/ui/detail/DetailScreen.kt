package com.lawquery.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lawquery.R
import com.lawquery.domain.model.Chapter
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.ui.common.ErrorState
import com.lawquery.ui.common.FetchedAtBar
import com.lawquery.ui.common.LawStatusBadge
import com.lawquery.ui.common.LoadingState
import com.lawquery.ui.common.OfflineState
import com.lawquery.ui.common.SourceBadge
import com.lawquery.ui.common.SourceNames
import com.lawquery.ui.common.UiState
import com.lawquery.ui.theme.HighlightAmber
import com.lawquery.ui.theme.ReadingStyle
import com.lawquery.ui.theme.StarGold
import com.lawquery.util.TimeText
import kotlinx.coroutines.launch

/** 详情页渲染行模型 */
private sealed interface DetailRow {
    val key: String

    data class Preamble(val paragraphs: List<String>) : DetailRow {
        override val key = "preamble"
    }

    data class ChapterRow(val chapter: Chapter, val index: Int) : DetailRow {
        override val key = "chapter-${chapter.title}-$index"
    }

    data class SectionRow(val title: String, val chapterIndex: Int, val sectionIndex: Int) : DetailRow {
        override val key = "section-$chapterIndex-$sectionIndex"
    }

    data class ArticleRow(val article: LawArticle, val chapterIndex: Int) : DetailRow {
        override val key = "article-$chapterIndex-${article.number}-${article.paragraphs.hashCode()}"
    }
}

private fun buildRows(doc: LawDocument, expanded: Set<Int>): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    if (doc.preamble.isNotEmpty()) rows += DetailRow.Preamble(doc.preamble)
    val collapse = doc.chapters.size > 1
    doc.chapters.forEachIndexed { ci, ch ->
        rows += DetailRow.ChapterRow(ch, ci)
        if (collapse && !expanded.contains(ci)) return@forEachIndexed
        ch.sections.forEachIndexed { si, sec ->
            if (sec.title.isNotBlank()) rows += DetailRow.SectionRow(sec.title, ci, si)
            sec.articles.forEach { art ->
                rows += DetailRow.ArticleRow(art, ci)
            }
        }
    }
    return rows
}

/** 目录 Tab 渲染行模型:章/节/条,受 expanded 状态驱动 */
private sealed interface TocRow {
    val key: String

    data class ChapterRow(val chapter: Chapter, val index: Int) : TocRow {
        override val key = "toc-chapter-$index"
    }

    data class SectionRow(val title: String, val chapterIndex: Int, val sectionIndex: Int) : TocRow {
        override val key = "toc-section-$chapterIndex-$sectionIndex"
    }

    data class ArticleRow(val article: LawArticle, val chapterIndex: Int) : TocRow {
        override val key = "toc-article-$chapterIndex-${article.number}-${article.paragraphs.hashCode()}"
    }
}

/** 目录行构建:章始终显示;章仅在 expanded 中时展开其节与条 */
private fun buildTocRows(doc: LawDocument, expanded: Set<Int>): List<TocRow> {
    val rows = mutableListOf<TocRow>()
    doc.chapters.forEachIndexed { ci, ch ->
        rows += TocRow.ChapterRow(ch, ci)
        if (!expanded.contains(ci)) return@forEachIndexed
        ch.sections.forEachIndexed { si, sec ->
            if (sec.title.isNotBlank()) rows += TocRow.SectionRow(sec.title, ci, si)
            sec.articles.forEach { art ->
                rows += TocRow.ArticleRow(art, ci)
            }
        }
    }
    return rows
}

private fun selectedArticleText(doc: LawDocument?, key: String?): Pair<String, String>? {
    if (doc == null || key == null) return null
    for ((ci, ch) in doc.chapters.withIndex()) {
        for (sec in ch.sections) {
            for (art in sec.articles) {
                val rowKey = "article-$ci-${art.number}-${art.paragraphs.hashCode()}"
                if (rowKey == key) return art.displayNumber to art.text
            }
        }
    }
    return null
}

/** 复制/分享文案:条文 + 来源 + 原文链接(需求 7.2.5 / 验收 C4/E6) */
private fun shareTextOf(doc: LawDocument, articleText: String): String =
    articleText + "\n\n来源:" + SourceNames.displayName(doc.ref.source) +
        "　原文链接:" + doc.ref.url

/** 全文文本(复制全文用):标题 + 前言 + 全部条文 + 来源/链接 */
private fun docFullTextOf(doc: LawDocument): String = buildString {
    appendLine(doc.ref.title)
    doc.preamble.forEach { appendLine(it) }
    doc.chapters.forEach { ch ->
        if (ch.title.isNotBlank()) appendLine(ch.title)
        ch.articles.forEach { art -> appendLine(art.displayNumber + "　" + art.text) }
    }
    appendLine()
    append("来源:" + SourceNames.displayName(doc.ref.source) + "　原文链接:" + doc.ref.url)
}

private fun locateArticleInDoc(doc: LawDocument?, located: Triple<Int, Int, Int>): LawArticle {
    val chapter = doc?.chapters?.getOrNull(located.first)
    return chapter?.sections?.getOrNull(located.second)?.articles?.getOrNull(located.third)
        ?: LawArticle("", null, emptyList())
}

/** 命中词琥珀黄底 AnnotatedString(条内搜索) */
private fun highlightSpans(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val q = query.trim()
    if (q.isNotEmpty()) {
        var from = 0
        while (true) {
            val idx = text.indexOf(q, from, ignoreCase = true)
            if (idx < 0) break
            addStyle(SpanStyle(background = HighlightAmber), idx, idx + q.length)
            from = idx + q.length
        }
    }
}

/**
 * 原生详情页(参考图风格):
 * 元信息区常显、正文/目录 Tab、章标题紫底高亮、条内搜索(琥珀黄命中)、
 * 收藏全文/复制全文、单条复制/分享(附带来源与原文链接)、
 * 底部「上一条/下一条」翻条器与进度、字号 5 档 + 行间距 3 档全局记忆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenOriginal: (LawRef) -> Unit,
    /** 案例库会话失效时:打开官方登录页重新建立会话 */
    onOpenCaseLogin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val needsLogin by viewModel.needsLogin.collectAsStateWithLifecycle()
    val failureReason by viewModel.failureReason.collectAsStateWithLifecycle()
    val expanded by viewModel.expandedChapters.collectAsStateWithLifecycle()
    val highlight by viewModel.highlightArticle.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selectedArticleKey.collectAsStateWithLifecycle()
    val fontSizeIndex by viewModel.fontSizeIndex.collectAsStateWithLifecycle()
    val lineSpacingIndex by viewModel.lineSpacingIndex.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val updatePrompt by viewModel.updatePrompt.collectAsStateWithLifecycle()
    val detailTab by viewModel.detailTab.collectAsStateWithLifecycle()
    val articleQuery by viewModel.articleQuery.collectAsStateWithLifecycle()
    val currentOrdinal by viewModel.currentOrdinal.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var articleSearchActive by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf(false) }

    val doc = (state as? UiState.Content)?.data
    val flat = viewModel.flatArticles(doc)
    val lineFactor = ReadingStyle.lineSpacing(lineSpacingIndex)

    /**
     * 正文改由官方阅读器呈现(国家法律法规数据库)。
     *
     * 该源的正文是官方版式文件,官方不下发可抽取的文本层(实测阅读器文本接口
     * 返回空版面数据),故正文区嵌官方阅读器 —— 官方授权的阅读形态。
     * 这种情况下:章节 Tab / 条内搜索 / 翻条器都不适用(应用侧无条文结构),
     * 只保留元信息区 + 阅读器,并把「查看原文」留在元信息区。
     */
    val readerUrl = doc?.readerUrl
    val useReader = !readerUrl.isNullOrBlank()

    /**
     * 阅读器模式的两个会话内状态。
     *
     * - [readerInfoExpanded]:元信息区是否展开。默认**折叠** —— 常驻会吃掉过半屏高,
     *   正文区被压到半屏以下(v1.0.6 实测)。折叠后只留一条摘要行,正文占满剩余空间。
     * - [readerZoom]:阅读器缩放倍率,记忆本次阅读过程中的调整,切换正文 Tab 不丢。
     */
    var readerInfoExpanded by rememberSaveable { mutableStateOf(false) }
    var readerZoom by rememberSaveable { mutableStateOf(1f) }

    /** 跳转到指定条(展开章→计算行索引→滚动→高亮→同步翻条器) */
    fun scrollToArticle(chapterIndex: Int, article: LawArticle) {
        if (article.number.isBlank() && article.paragraphs.isEmpty()) return
        viewModel.expandChapter(chapterIndex)
        val rows = doc?.let { buildRows(it, viewModel.expandedChapters.value) } ?: emptyList()
        val index = rows.indexOfFirst { row ->
            row is DetailRow.ArticleRow && row.chapterIndex == chapterIndex && row.article == article
        }
        if (index >= 0) {
            scope.launch {
                // 可能刚从目录/搜索切换回正文:等一帧让列表完成组合后再滚动
                withFrameNanos { }
                listState.animateScrollToItem(index)
            }
            // 无条号文档(司法案例)没有条号可高亮,改用选中态给出定位反馈
            if (article.arabicNumber == null) viewModel.selectArticle(rows[index].key)
        }
        viewModel.highlight(article.arabicNumber)
        val ordinal = flat.indexOfFirst { it.first == chapterIndex && it.second == article }
        if (ordinal >= 0) viewModel.setCurrentOrdinal(ordinal + 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    title = {
                        Text(doc?.ref?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        // 条内搜索开关(参考图「搜索条文」):阅读器模式下应用侧无条文结构,隐藏
                        if (!useReader) {
                            IconButton(onClick = {
                                articleSearchActive = !articleSearchActive
                                if (!articleSearchActive) viewModel.setArticleQuery("")
                            }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.detail_search_in_doc),
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(
                                    if (isFavorite) R.string.detail_favorite_remove else R.string.detail_favorite_add
                                ),
                                tint = if (isFavorite) StarGold else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
                // 条内搜索框
                if (articleSearchActive) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        OutlinedTextField(
                            value = articleQuery,
                            onValueChange = { viewModel.setArticleQuery(it) },
                            placeholder = {
                                Text(
                                    stringResource(R.string.detail_search_in_doc_hint),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (articleQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setArticleQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                // 正文/目录 Tab(参考图):阅读器模式下没有本地条文结构,不需要目录 Tab
                if (doc != null && doc.chapters.isNotEmpty() && !articleSearchActive && !useReader) {
                    TabRow(
                        selectedTabIndex = detailTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Tab(
                            selected = detailTab == 0,
                            onClick = { viewModel.setDetailTab(0) },
                            text = { Text(stringResource(R.string.detail_tab_content)) },
                        )
                        Tab(
                            selected = detailTab == 1,
                            onClick = { viewModel.setDetailTab(1) },
                            text = { Text(stringResource(R.string.detail_tab_toc)) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            // 翻条器(参考图):上一条 / 章名+进度 / 下一条(阅读器模式下无条文,不显示)
            if (doc != null && flat.isNotEmpty() && detailTab == 0 && !articleSearchActive && !useReader) {
                val ordinal = currentOrdinal.coerceIn(1, flat.size)
                val currentChapter = doc.chapters.getOrNull(flat[ordinal - 1].first)
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = {
                                    val target = flat.getOrNull(ordinal - 2) ?: return@TextButton
                                    viewModel.setCurrentOrdinal(ordinal - 1)
                                    scrollToArticle(target.first, target.second)
                                },
                                enabled = ordinal > 1,
                            ) { Text("‹ " + stringResource(R.string.detail_prev_article)) }
                            Text(
                                currentChapter?.title?.ifBlank { stringResource(R.string.detail_tab_content) } ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val target = flat.getOrNull(ordinal) ?: return@TextButton
                                    viewModel.setCurrentOrdinal(ordinal + 1)
                                    scrollToArticle(target.first, target.second)
                                },
                                enabled = ordinal < flat.size,
                            ) { Text(stringResource(R.string.detail_next_article) + " ›") }
                        }
                        LinearProgressIndicator(
                            progress = { ordinal.toFloat() / flat.size },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                        Text(
                            "$ordinal / ${flat.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (state) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))
            is UiState.Offline -> OfflineState(Modifier.padding(padding), onRetry = viewModel::load)
            is UiState.Error -> if (needsLogin) {
                // 案例库会话失效:给出明确的重新登录入口(而非笼统的"获取失败")
                Column(Modifier.padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    // fill=false:不占满高度,给下方的说明与按钮留出位置
                    ErrorState(Modifier, onRetry = viewModel::load, fill = false)
                    Text(
                        stringResource(R.string.alk_session_expired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Row {
                        Button(
                            onClick = onOpenCaseLogin,
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.alk_relogin))
                        }
                        Spacer(Modifier.width(8.dp))
                        // 兜底:即使解析失败也能到官方页面核对原文
                        OutlinedButton(
                            onClick = { onOpenOriginal(viewModel.ref) },
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.detail_view_original))
                        }
                    }
                }
            } else {
                // 付费墙(官方仅下发正文预览)不是故障:换标题并给出去官方页继续阅读的指引,
                // 避免用户看到「获取失败」后反复点重试(重试无用,须在官方页阅读全文)
                val previewOnly = failureReason == com.lawquery.data.source.FailureReason.PREVIEW_ONLY
                Column(Modifier.padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorState(Modifier, onRetry = viewModel::load, fill = false)
                    failureReason?.let { reason ->
                        Text(
                            failureReasonText(reason),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { onOpenOriginal(viewModel.ref) },
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (previewOnly) R.string.detail_read_full_official
                                else R.string.detail_view_original,
                            ),
                        )
                    }
                }
            }
            is UiState.Empty -> ErrorState(Modifier.padding(padding), onRetry = viewModel::load)
            is UiState.Content -> {
                val d = (state as UiState.Content).data

                if (articleSearchActive) {
                    // 条内搜索结果(参考图「搜索条文」)
                    ArticleSearchResults(
                        modifier = Modifier.padding(padding),
                        hits = viewModel.articleMatches(d, articleQuery),
                        query = articleQuery,
                        onOpenHit = { hit ->
                            articleSearchActive = false
                            viewModel.setDetailTab(0)
                            viewModel.setArticleQuery("")
                            scrollToArticle(hit.chapterIndex, hit.article)
                        },
                    )
                } else if (useReader) {
                    // 官方阅读器正文。
                    //
                    // 布局要点(v1.0.6 截图后调整):元信息区默认**折叠**,只留一条
                    // 摘要行 + 「展开资料」按钮,把绝大部分屏高让给正文 ——
                    // 此前元信息常驻会吃掉 55% 屏高,阅读区只剩半屏且字号过小。
                    val expanded = readerInfoExpanded
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        ReaderSummaryBar(
                            ref = d.ref,
                            expanded = expanded,
                            onToggle = { readerInfoExpanded = !expanded },
                        )
                        if (expanded) {
                            DocMetaHeader(
                                ref = d.ref,
                                fetchedAtText = TimeText.dateTime(d.fetchedAt),
                                preamble = d.preamble,
                                isFavorite = isFavorite,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onOpenOriginal = { onOpenOriginal(d.ref) },
                                onCollapse = { readerInfoExpanded = false },
                            )
                        }
                        OfficialReaderBody(
                            readerUrl = readerUrl!!,
                            initialZoom = readerZoom,
                            onZoomChange = { readerZoom = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                } else {
                    Column(Modifier.padding(padding)) {
                        when (detailTab) {
                            1 -> DirectoryTab(
                                document = d,
                                expanded = expanded,
                                jumpInput = jumpInput,
                                jumpError = jumpError,
                                onJumpInput = { jumpInput = it; jumpError = false },
                                onExpandAll = viewModel::expandAll,
                                onCollapseAll = viewModel::collapseAll,
                                onToggleChapter = viewModel::toggleChapter,
                                /** 直达:数字按条号,文字按板块/章/节名称(见 locateByTitle 规则) */
                                onJump = {
                                    val q = jumpInput.trim()
                                    val number = q.toIntOrNull()
                                    if (q.isEmpty()) {
                                        jumpError = true
                                    } else if (number != null) {
                                        // 条号直达(行政法规、司法解释等有条号的文档)
                                        val located = viewModel.locateArticle(number)
                                        if (located == null) {
                                            jumpError = true
                                        } else {
                                            jumpError = false
                                            // 必须先切回正文 Tab:目录 Tab 下正文列表未组合,
                                            // 滚动无效且用户无感知(与 onOpenChapter 同一处理)
                                            viewModel.setDetailTab(0)
                                            scrollToArticle(located.first, locateArticleInDoc(d, located))
                                        }
                                    } else {
                                        // 名称直达(司法案例:裁判要旨/基本案情/…)
                                        val hit = viewModel.locateByTitle(q)
                                        val target = hit?.let { viewModel.firstArticleAt(it.first, it.second) }
                                        if (hit == null || target == null) {
                                            jumpError = true
                                        } else {
                                            jumpError = false
                                            viewModel.setDetailTab(0)
                                            scrollToArticle(hit.first, target)
                                        }
                                    }
                                },
                                onOpenChapter = { ci ->
                                    // 章(板块)标题:切回正文并定位到该章首条
                                    viewModel.setDetailTab(0)
                                    viewModel.expandChapter(ci)
                                    viewModel.firstArticleAt(ci, -1)?.let { scrollToArticle(ci, it) }
                                },
                                onOpenSection = { ci, si ->
                                    // 节标题:同样定位到该节首条
                                    viewModel.setDetailTab(0)
                                    viewModel.expandChapter(ci)
                                    viewModel.firstArticleAt(ci, si)?.let { scrollToArticle(ci, it) }
                                },
                                onOpenArticle = { ci, art ->
                                    // 点击目录中的条文条目:切回正文 Tab 并滚动高亮
                                    viewModel.setDetailTab(0)
                                    scrollToArticle(ci, art)
                                },
                            )
                            else -> DetailBody(
                                document = d,
                                rows = buildRows(d, expanded),
                                highlight = highlight,
                                selectedKey = selectedKey,
                                fontSizeIndex = fontSizeIndex,
                                lineSpacingFactor = lineFactor,
                                listState = listState,
                                isFavorite = isFavorite,
                                onSelectArticle = viewModel::selectArticle,
                                onToggleChapter = viewModel::toggleChapter,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onCopyDocFull = {
                                    clipboard.setText(AnnotatedString(docFullTextOf(d)))
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.detail_doc_copied))
                                    }
                                },
                                onOpenOriginal = { onOpenOriginal(d.ref) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 高亮 3 秒后自动清除
    LaunchedEffect(highlight) {
        if (highlight != null) {
            kotlinx.coroutines.delay(3_000)
            viewModel.highlight(null)
        }
    }

    // 收藏更新提示(需求 D3)
    updatePrompt?.let { latest ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            title = { Text(stringResource(R.string.detail_updated_title)) },
            text = { Text(stringResource(R.string.detail_updated_message, TimeText.date(latest.publishDate))) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmUpdate(latest) }) {
                    Text(stringResource(R.string.detail_updated_view))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate) {
                    Text(stringResource(R.string.detail_updated_later))
                }
            },
        )
    }
}

/** 条内搜索结果(章名 + 条文,命中词琥珀黄底) */
@Composable
private fun ArticleSearchResults(
    modifier: Modifier,
    hits: List<DetailViewModel.ArticleHit>,
    query: String,
    onOpenHit: (DetailViewModel.ArticleHit) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "count") {
            Text(
                if (query.isBlank()) "" else stringResource(R.string.detail_search_in_doc_count, hits.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (hits.isEmpty() && query.isNotBlank()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.detail_search_in_doc_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        itemsIndexed(
            hits,
            key = { _, hit -> hit.chapterIndex.toString() + "-" + hit.article.number + "-" + hit.article.paragraphs.hashCode() },
        ) { _, hit ->
            androidx.compose.material3.Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .clickable { onOpenHit(hit) },
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (hit.chapterTitle.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                hit.chapterTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hit.article.displayNumber,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    hit.article.paragraphs.forEach { p ->
                        Text(
                            highlightSpans(p, query),
                            fontSize = 15.sp,
                            lineHeight = 15.sp * 1.7f,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 目录 Tab(参考图条文目录页):章可展开/折叠,受 expandedChapters 状态驱动 */
@Composable
private fun DirectoryTab(
    document: LawDocument,
    expanded: Set<Int>,
    jumpInput: String,
    jumpError: Boolean,
    onJumpInput: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onToggleChapter: (Int) -> Unit,
    onJump: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onOpenSection: (Int, Int) -> Unit,
    onOpenArticle: (Int, LawArticle) -> Unit,
) {
    // expanded 变化时重建目录行,保证「全部展开/全部折叠/单章点按」即时生效
    val tocRows = remember(document, expanded) { buildTocRows(document, expanded) }
    LazyColumn(Modifier.fillMaxSize().testTag("directory_list")) {
        item(key = "doc") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        document.ref.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    LawStatusBadge(document.ref.status)
                }
            }
        }
        item(key = "jump") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = jumpInput,
                    // 允许输入条号(数字)或板块/章节名称,故不再限制为数字
                    onValueChange = { v -> onJumpInput(v.take(24)) },
                    label = { Text(stringResource(R.string.detail_toc_jump_hint)) },
                    singleLine = true,
                    isError = jumpError,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onJump) { Text(stringResource(R.string.detail_toc_jump)) }
            }
            if (jumpError) {
                Text(
                    stringResource(R.string.detail_article_not_found),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = onExpandAll) { Text(stringResource(R.string.detail_toc_expand_all)) }
                TextButton(onClick = onCollapseAll) { Text(stringResource(R.string.detail_toc_collapse_all)) }
            }
        }
        itemsIndexed(tocRows, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is TocRow.ChapterRow -> {
                    val isExpanded = expanded.contains(row.index)
                    // 行内分工:点标题 = 定位到该章(板块);点右侧箭头 = 展开/折叠。
                    // 拆分点击区域避免"跳转"与"折叠"互相抢占(此前整行只能折叠,点章名不跳转)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.chapter.title.ifBlank { stringResource(R.string.detail_toc) + " ${row.index + 1}" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenChapter(row.index) }
                                .padding(vertical = 8.dp),
                        )
                        IconButton(onClick = { onToggleChapter(row.index) }) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = stringResource(
                                    if (isExpanded) R.string.detail_toc_collapse else R.string.detail_toc_expand
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (isExpanded) 180f else 0f),
                            )
                        }
                    }
                }
                is TocRow.SectionRow -> {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSection(row.chapterIndex, row.sectionIndex) }
                            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                is TocRow.ArticleRow -> {
                    // 无条号的文档(司法案例)用段落首句摘要作为目录项文本,保证每一项都可读、可点
                    val label = row.article.displayNumber.ifBlank {
                        row.article.paragraphs.firstOrNull().orEmpty().take(TOC_SUMMARY_CHARS)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenArticle(row.chapterIndex, row.article) }
                            .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 阅读器模式的元信息摘要条(默认展示,可展开为完整资料区)。
 *
 * 折叠态只占一行:标题 + 时效性/来源徽标 + 「展开法规资料」。
 * 这样正文能拿到绝大部分屏高,用户需要看制定机关/日期/文号时再展开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSummaryBar(
    ref: LawRef,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ref.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.detail_info_collapse else R.string.detail_info_expand
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LawStatusBadge(ref.status)
                SourceBadge(ref.source)
                Text(
                    stringResource(
                        if (expanded) R.string.detail_info_collapse else R.string.detail_info_expand
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 文档元信息区(标题/时效性/来源/文号/日期/制定机关 + 收藏 + 查看原文)。
 *
 * 原生正文([DetailBody])与官方阅读器正文([OfficialReaderBody])共用这一份头部,
 * 保证两类来源的详情页观感一致(需求 F3:元信息区常显)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocMetaHeader(
    ref: LawRef,
    fetchedAtText: String,
    preamble: List<String>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenOriginal: () -> Unit,
    /** 阅读器模式:在资料区底部给一个「收起,全屏阅读」的回退入口(原生正文模式不传) */
    onCollapse: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        FetchedAtBar(
            sourceName = SourceNames.displayName(ref.source),
            fetchedAtText = fetchedAtText,
        )
        Column(Modifier.padding(16.dp)) {
            Text(ref.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LawStatusBadge(ref.status)
                SourceBadge(ref.source)
            }
            Spacer(Modifier.height(10.dp))
            // 文号 + 公布/施行日期:各条按自身内容取宽,一行放不下自动换行。
            // 用 Row 会在文号很长时把日期压到近零宽、渲染成一字一行的竖排。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!ref.docNumber.isNullOrBlank()) {
                    Text(
                        ref.docNumber!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (ref.publishDate != null) {
                    Text(
                        stringResource(R.string.detail_publish_suffix, TimeText.date(ref.publishDate)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ref.effectiveDate != null) {
                    Text(
                        stringResource(R.string.detail_effective_suffix, TimeText.date(ref.effectiveDate)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ref.issuingAuthority.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.detail_authority) + "  " + ref.issuingAuthority,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 官方题录补充项(效力位阶/公布与施行日期等,flk 详情接口独有)
            val extras = preamble.filter { it.contains(':') || it.contains("：") }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                extras.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 行内操作:收藏全文 / 查看原文(复制全文不适用于阅读器正文,故不在此提供)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = if (isFavorite) StarGold else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.detail_collect_doc))
                }
            }
            OutlinedButton(onClick = onOpenOriginal) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.detail_view_original))
            }
            // 阅读器模式:展开后的回退入口 —— 收起资料让正文占满屏幕
            if (onCollapse != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onCollapse) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.detail_info_collapse))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    document: LawDocument,
    rows: List<DetailRow>,
    highlight: Int?,
    selectedKey: String?,
    fontSizeIndex: Int,
    lineSpacingFactor: Float,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isFavorite: Boolean,
    onSelectArticle: (String?) -> Unit,
    onToggleChapter: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyDocFull: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    val ref = document.ref
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 元信息区(置顶常显,需求 C1;参考图 chips 样式)
        item(key = "meta") {
            Column(Modifier.fillMaxWidth()) {
                DocMetaHeader(
                    ref = ref,
                    fetchedAtText = TimeText.dateTime(document.fetchedAt),
                    preamble = document.preamble,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onOpenOriginal = onOpenOriginal,
                )
                // 复制全文:仅原生正文可得(阅读器正文不在此处)
                Column(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = onCopyDocFull) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.detail_copy_doc))
                    }
                }
            }
        }

        if (document.isPlainTextFallback) {
            item(key = "fallback") {
                Text(
                    stringResource(R.string.detail_plain_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                )
            }
        }

        // 仅开放部分正文(官方订购权限限制):下面是可以正常阅读的内容,
        // 末尾给到官方页读全文的入口 —— 不冒充完整正文,也不整篇判失败
        if (document.isPartial) {
            item(key = "partial") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.detail_partial_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onOpenOriginal) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.detail_view_full))
                    }
                }
            }
        }

        // 正文(章 → 节 → 条;按章懒加载)
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is DetailRow.Preamble -> {
                    val fs = ReadingStyle.sp(fontSizeIndex)
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        row.paragraphs.forEach { p ->
                            Text(
                                p,
                                fontSize = fs.sp,
                                lineHeight = (fs * lineSpacingFactor).sp,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                is DetailRow.ChapterRow -> {
                    // 章标题紫底高亮(参考图)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onToggleChapter(row.index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.chapter.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                is DetailRow.SectionRow -> {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                is DetailRow.ArticleRow -> {
                    val fs = ReadingStyle.sp(fontSizeIndex)
                    val isHighlighted = highlight != null && row.article.arabicNumber == highlight
                    val isSelected = selectedKey == row.key
                    val bg by animateColorAsState(
                        when {
                            isHighlighted -> HighlightAmber.copy(alpha = 0.45f)
                            isSelected -> MaterialTheme.colorScheme.surfaceVariant
                            else -> Color.Transparent
                        },
                        label = "articleBg",
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .clickable { onSelectArticle(if (isSelected) null else row.key) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            row.article.displayNumber,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = fs.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        row.article.paragraphs.forEach { p ->
                            Text(p, fontSize = fs.sp, lineHeight = (fs * lineSpacingFactor).sp)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/** 取正文失败原因 → 用户可读文案(便于把"获取失败"落到具体成因) */
@Composable
private fun failureReasonText(reason: com.lawquery.data.source.FailureReason): String = when (reason) {
    com.lawquery.data.source.FailureReason.RATE_LIMITED -> stringResource(R.string.source_reason_rate_limited)
    com.lawquery.data.source.FailureReason.TIMEOUT -> stringResource(R.string.source_reason_timeout)
    com.lawquery.data.source.FailureReason.NETWORK -> stringResource(R.string.source_reason_network)
    com.lawquery.data.source.FailureReason.OFFLINE -> stringResource(R.string.source_reason_offline)
    com.lawquery.data.source.FailureReason.PARSE_FAILED -> stringResource(R.string.source_reason_parse_failed)
    com.lawquery.data.source.FailureReason.NOT_LINKED -> stringResource(R.string.source_reason_not_linked)
    // 官方只给正文预览(账号未订购该效力位阶):不是故障,点「查看原文」到官网读全文
    com.lawquery.data.source.FailureReason.PREVIEW_ONLY -> stringResource(R.string.source_reason_preview_only)
}

/** 目录中无条号条目的摘要长度(司法案例的段落以首句为目录名) */
private const val TOC_SUMMARY_CHARS = 24
