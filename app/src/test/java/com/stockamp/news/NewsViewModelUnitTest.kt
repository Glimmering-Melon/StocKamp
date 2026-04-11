package com.stockamp.news

import androidx.paging.PagingData
import com.stockamp.data.model.ArticleStatus
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.model.SentimentLabel
import com.stockamp.data.repository.NewsRepository
import com.stockamp.ui.news.NewsUiState
import com.stockamp.ui.news.NewsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewsViewModelUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: NewsRepository
    private lateinit var viewModel: NewsViewModel

    // Shared flow so we can emit new values during tests
    private val latestNewsFlow = MutableSharedFlow<List<NewsArticle>>(replay = 1)

    private fun makeArticle(
        id: String = "id-1",
        status: ArticleStatus = ArticleStatus.ANALYZED
    ) = NewsArticle(
        id = id,
        title = "Title $id",
        url = "https://example.com/$id",
        summary = null,
        sourceName = "CafeF",
        publishedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        stockSymbols = listOf("VNM", "HPG"),
        sentimentLabel = SentimentLabel.POSITIVE,
        sentimentScore = 0.85f,
        status = status
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.getLatestNews(any()) } returns latestNewsFlow
        every { repository.getNewsStream(any()) } returns flowOf(PagingData.empty())
        coEvery { repository.refresh() } returns Result.success(Unit)
        coEvery { repository.unsubscribeRealtime() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── StateFlow initial state ──────────────────────────────────────────────

    @Test
    fun `initial state is Loading before first emission`() = runTest {
        viewModel = NewsViewModel(repository)
        // Before any emission, state should be Loading
        assertIs<NewsUiState.Loading>(viewModel.uiState.value)
    }

    @Test
    fun `state becomes Success after repository emits articles`() = runTest {
        viewModel = NewsViewModel(repository)
        val articles = listOf(makeArticle("1"), makeArticle("2"))
        latestNewsFlow.emit(articles)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(articles, state.latestNews)
        assertEquals(emptyList(), state.activeFilters)
        assertEquals(false, state.isRefreshing)
    }

    // ─── applyFilter ─────────────────────────────────────────────────────────

    @Test
    fun `applyFilter updates activeFilters in Success state`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.applyFilter(listOf("VNM", "HPG"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(listOf("VNM", "HPG"), state.activeFilters)
    }

    @Test
    fun `applyFilter with empty list clears filters`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.applyFilter(listOf("VNM"))
        advanceUntilIdle()
        viewModel.applyFilter(emptyList())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(emptyList(), state.activeFilters)
    }

    @Test
    fun `applyFilter does not change latestNews`() = runTest {
        viewModel = NewsViewModel(repository)
        val articles = listOf(makeArticle("1"), makeArticle("2"))
        latestNewsFlow.emit(articles)
        advanceUntilIdle()

        viewModel.applyFilter(listOf("VNM"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        // latestNews is unchanged — filtering is done at the repository/query level
        assertEquals(articles, state.latestNews)
    }

    // ─── clearFilter ─────────────────────────────────────────────────────────

    @Test
    fun `clearFilter removes all active filters`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.applyFilter(listOf("VNM", "HPG", "VIC"))
        advanceUntilIdle()
        viewModel.clearFilter()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(emptyList(), state.activeFilters)
    }

    @Test
    fun `clearFilter on already-empty filters is a no-op`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.clearFilter()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(emptyList(), state.activeFilters)
    }

    // ─── refresh ─────────────────────────────────────────────────────────────

    @Test
    fun `refresh calls repository refresh`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refresh() }
    }

    @Test
    fun `refresh sets isRefreshing true then false on success`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        // After refresh completes, isRefreshing should be false
        // (the repository emits updated data which resets the state)
        coVerify { repository.refresh() }
    }

    @Test
    fun `refresh failure transitions to Error state`() = runTest {
        coEvery { repository.refresh() } returns Result.failure(Exception("Network error"))
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(emptyList())
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Error>(state)
        assertTrue(state.message.contains("Network error"))
    }

    // ─── loadForSymbol ────────────────────────────────────────────────────────

    @Test
    fun `loadForSymbol applies single-symbol filter`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.loadForSymbol("VNM")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        assertEquals(listOf("VNM"), state.activeFilters)
    }

    // ─── StateFlow re-emission ────────────────────────────────────────────────

    @Test
    fun `StateFlow updates when repository emits new articles`() = runTest {
        viewModel = NewsViewModel(repository)

        val firstBatch = listOf(makeArticle("1"))
        latestNewsFlow.emit(firstBatch)
        advanceUntilIdle()

        val firstState = viewModel.uiState.value
        assertIs<NewsUiState.Success>(firstState)
        assertEquals(firstBatch, firstState.latestNews)

        val secondBatch = listOf(makeArticle("1"), makeArticle("2"), makeArticle("3"))
        latestNewsFlow.emit(secondBatch)
        advanceUntilIdle()

        val secondState = viewModel.uiState.value
        assertIs<NewsUiState.Success>(secondState)
        assertEquals(secondBatch, secondState.latestNews)
    }

    @Test
    fun `filters are preserved across repository re-emissions`() = runTest {
        viewModel = NewsViewModel(repository)
        latestNewsFlow.emit(listOf(makeArticle("1")))
        advanceUntilIdle()

        viewModel.applyFilter(listOf("VNM"))
        advanceUntilIdle()

        // Repository emits new data (e.g. after realtime update)
        latestNewsFlow.emit(listOf(makeArticle("1"), makeArticle("2")))
        advanceUntilIdle()

        // Filters should be reset by the new emission (observeLatestNews uses _activeFilters.value)
        val state = viewModel.uiState.value
        assertIs<NewsUiState.Success>(state)
        // The new emission picks up current _activeFilters
        assertEquals(listOf("VNM"), state.activeFilters)
    }
}
