package com.lawquery.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lawquery.data.local.SettingsStore
import com.lawquery.data.repo.FavoriteRepository
import com.lawquery.data.repo.LawDetailRepository
import com.lawquery.data.source.FailureReason
import com.lawquery.domain.model.LawArticle
import com.lawquery.domain.model.LawDocument
import com.lawquery.domain.model.LawRef
import com.lawquery.domain.model.VersionFingerprint
import com.lawquery.ui.common.UiState
import com.lawquery.util.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 条文详情(需求 F3/F4/6.2):实时获取、会话缓存命中时带原 fetchedAt、
 * 收藏打开时先做版本指纹校验。
 */
class DetailViewModel(
    private val lawDetailRepository: LawDetailRepository,
    private val favoriteRepository: FavoriteRepository,
    private val settingsStore: SettingsStore,
    private val networkMonitor: NetworkMonitor,
    val ref: LawRef,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<LawDocument>>(UiState.Loading)
    val state: StateFlow<UiState<LawDocument>> = _state.asStateFlow()

    /** 取正文失败是否因官方会话失效(案例库):为 true 时详情页显示重新登录入口 */
    private val _needsLogin = MutableStateFlow(false)
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    /** 最近一次取正文失败的原因(用于把"获取失败"落到具体成因,便于用户与排查) */
    private val _failureReason = MutableStateFlow<FailureReason?>(null)
    val failureReason: StateFlow<FailureReason?> = _failureReason.asStateFlow()

    /** 展开的章索引;长文默认折叠到目录态(需求 F3) */
    private val _expandedChapters = MutableStateFlow<Set<Int>>(emptySet())
    val expandedChapters: StateFlow<Set<Int>> = _expandedChapters.asStateFlow()

    /** 条号直达后的高亮条号(需求 C3) */
    private val _highlightArticle = MutableStateFlow<Int?>(null)
    val highlightArticle: StateFlow<Int?> = _highlightArticle.asStateFlow()

    /** 选中条(点击弹出操作条:复制/分享) */
    private val _selectedArticleKey = MutableStateFlow<String?>(null)
    val selectedArticleKey: StateFlow<String?> = _selectedArticleKey.asStateFlow()

    val fontSizeIndex: StateFlow<Int> = settingsStore.fontSizeIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2)

    val isFavorite: StateFlow<Boolean> = favoriteRepository.observeExists(ref.key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 行间距档位 0..2(紧凑/适中/宽松),全局记忆(需求 F6) */
    val lineSpacingIndex: StateFlow<Int> = settingsStore.lineSpacingIndex
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    /** 正文/目录 tab(参考图:0=正文 1=目录) */
    private val _detailTab = MutableStateFlow(0)
    val detailTab: StateFlow<Int> = _detailTab.asStateFlow()

    fun setDetailTab(index: Int) {
        _detailTab.value = index
    }

    /** 条内搜索(参考图「搜索条文」):对已加载文档的条文做本地过滤 */
    private val _articleQuery = MutableStateFlow("")
    val articleQuery: StateFlow<String> = _articleQuery.asStateFlow()

    fun setArticleQuery(q: String) {
        _articleQuery.value = q
    }

    data class ArticleHit(val chapterTitle: String, val chapterIndex: Int, val article: com.lawquery.domain.model.LawArticle)

    fun articleMatches(doc: LawDocument?, query: String): List<ArticleHit> {
        val q = query.trim()
        if (doc == null || q.isEmpty()) return emptyList()
        val hits = mutableListOf<ArticleHit>()
        doc.chapters.forEachIndexed { ci, ch ->
            ch.articles.forEach { art ->
                if (art.text.contains(q) || art.number.contains(q)) {
                    hits += ArticleHit(ch.title, ci, art)
                }
            }
        }
        return hits
    }

    /** 翻条器:当前条序号(1 起)与总数(参考图「上一条/下一条 + n/m」) */
    private val _currentOrdinal = MutableStateFlow(1)
    val currentOrdinal: StateFlow<Int> = _currentOrdinal.asStateFlow()

    fun flatArticles(doc: LawDocument?): List<Pair<Int, com.lawquery.domain.model.LawArticle>> {
        if (doc == null) return emptyList()
        val list = mutableListOf<Pair<Int, com.lawquery.domain.model.LawArticle>>()
        doc.chapters.forEachIndexed { ci, ch -> ch.articles.forEach { list += ci to it } }
        return list
    }

    fun totalArticles(doc: LawDocument?): Int = flatArticles(doc).size

    fun setCurrentOrdinal(ordinal: Int) {
        _currentOrdinal.value = ordinal.coerceAtLeast(1)
    }

    /** 收藏打开时发现官方元数据更新 → 弹提示(需求 D3) */
    private val _updatePrompt = MutableStateFlow<VersionFingerprint?>(null)
    val updatePrompt: StateFlow<VersionFingerprint?> = _updatePrompt.asStateFlow()

    init {
        load()
        // F7:离线自动重试;StateFlow 本身已去重,无需 distinctUntilChanged
        viewModelScope.launch {
            networkMonitor.online.collect { online ->
                if (online && _state.value is UiState.Offline) load()
            }
        }
        // 打开收藏时校验指纹(需求 6.2);失败静默,不打断阅读(需求 D5 不用旧内容冒充新内容)
        viewModelScope.launch {
            if (favoriteRepository.get(ref.key) != null) {
                when (val outcome = favoriteRepository.checkForUpdate(ref)) {
                    is FavoriteRepository.CheckOutcome.Updated -> _updatePrompt.value = outcome.latest
                    else -> Unit
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            when (val outcome = lawDetailRepository.getLaw(ref)) {
                is LawDetailRepository.Outcome.Ready -> {
                    val doc = outcome.document
                    // 短文默认全展开;章数 > 1 且条数多时默认折叠(目录态)
                    _expandedChapters.value =
                        if (doc.chapters.size <= 1 || doc.articleCount <= 40) {
                            doc.chapters.indices.toSet()
                        } else {
                            if (doc.chapters.isNotEmpty()) setOf(0) else emptySet()
                        }
                    _state.value = UiState.Content(doc)
                }
                is LawDetailRepository.Outcome.Unavailable -> {
                    // 案例库会话失效时明确引导重新登录,而不是笼统的"获取失败"
                    _needsLogin.value = outcome.reason == FailureReason.NOT_LINKED
                    _failureReason.value = outcome.reason
                    _state.value =
                        if (outcome.reason == FailureReason.OFFLINE) UiState.Offline else UiState.Error()
                }
            }
        }
    }

    fun toggleChapter(index: Int) {
        _expandedChapters.value = _expandedChapters.value.let {
            if (it.contains(index)) it - index else it + index
        }
    }

    /** 展开指定章(条号直达/目录跳章用,需求 C3) */
    fun expandChapter(index: Int) {
        _expandedChapters.value = _expandedChapters.value + index
    }

    fun expandAll() {
        val doc = (_state.value as? UiState.Content)?.data ?: return
        _expandedChapters.value = doc.chapters.indices.toSet()
    }

    fun collapseAll() {
        _expandedChapters.value = emptySet()
    }

    /** 查找条号所在位置(章索引,节索引,条索引);找不到返回 null(需求 C3) */
    fun locateArticle(number: Int): Triple<Int, Int, Int>? {
        val doc = (_state.value as? UiState.Content)?.data ?: return null
        doc.chapters.forEachIndexed { ci, ch ->
            ch.sections.forEachIndexed { si, sec ->
                sec.articles.forEachIndexed { ai, art ->
                    if (art.arabicNumber == number) return Triple(ci, si, ai)
                }
            }
        }
        return null
    }

    /**
     * 名称直达:按章/节标题定位(需求 C3 的补充通道,用于无条号文档,如司法案例)。
     *
     * 匹配规则(与目录展示名保持同一套命名):
     * 1) 归一化:去掉空白、全/半角标点与书名号等符号后再比较;
     * 2) 优先级:章标题精确 > 节标题精确 > 章标题包含 > 节标题包含;
     * 3) 命中的节索引为 -1 时表示命中章本身。
     */
    fun locateByTitle(query: String): Pair<Int, Int>? {
        val doc = (_state.value as? UiState.Content)?.data ?: return null
        val q = normalizeTitle(query)
        if (q.isEmpty()) return null
        doc.chapters.forEachIndexed { ci, ch ->
            if (normalizeTitle(ch.title) == q) return ci to -1
        }
        doc.chapters.forEachIndexed { ci, ch ->
            ch.sections.forEachIndexed { si, sec ->
                if (normalizeTitle(sec.title) == q) return ci to si
            }
        }
        doc.chapters.forEachIndexed { ci, ch ->
            if (normalizeTitle(ch.title).contains(q)) return ci to -1
        }
        doc.chapters.forEachIndexed { ci, ch ->
            ch.sections.forEachIndexed { si, sec ->
                if (normalizeTitle(sec.title).contains(q)) return ci to si
            }
        }
        return null
    }

    /** 章/节的层级名(用于跳转提示与翻条器显示) */
    fun titleAt(chapterIndex: Int, sectionIndex: Int): String {
        val doc = (_state.value as? UiState.Content)?.data ?: return ""
        val ch = doc.chapters.getOrNull(chapterIndex) ?: return ""
        if (sectionIndex < 0) return ch.title
        return ch.sections.getOrNull(sectionIndex)?.title?.takeIf { it.isNotBlank() } ?: ch.title
    }

    /** 章/节的首条内容,作为滚动定位目标(节索引 -1 表示整章) */
    fun firstArticleAt(chapterIndex: Int, sectionIndex: Int): LawArticle? {
        val doc = (_state.value as? UiState.Content)?.data ?: return null
        val ch = doc.chapters.getOrNull(chapterIndex) ?: return null
        return if (sectionIndex < 0) {
            ch.articles.firstOrNull()
        } else {
            ch.sections.getOrNull(sectionIndex)?.articles?.firstOrNull()
        }
    }

    private fun normalizeTitle(raw: String): String =
        raw.replace(TITLE_NOISE, "").lowercase()

    fun highlight(number: Int?) {
        _highlightArticle.value = number
    }

    fun selectArticle(key: String?) {
        _selectedArticleKey.value = key
    }

    fun confirmUpdate(latest: VersionFingerprint) {
        viewModelScope.launch {
            favoriteRepository.applyUpdate(ref.key, latest)
            _updatePrompt.value = null
        }
    }

    fun dismissUpdate() {
        _updatePrompt.value = null
    }

    fun toggleFavorite() {
        viewModelScope.launch { favoriteRepository.toggle(ref) }
    }

    class Factory(
        private val lawDetailRepository: LawDetailRepository,
        private val favoriteRepository: FavoriteRepository,
        private val settingsStore: SettingsStore,
        private val networkMonitor: NetworkMonitor,
        private val ref: LawRef,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(lawDetailRepository, favoriteRepository, settingsStore, networkMonitor, ref) as T
    }
}

/** 名称匹配时忽略的噪声字符:空白、中英标点与常见包裹符号(如《》、()、·) */
private val TITLE_NOISE = Regex("[\\s\\p{Punct}，。、；：？！（）〈〉《》【】「」『』〔〕·—…～]")
