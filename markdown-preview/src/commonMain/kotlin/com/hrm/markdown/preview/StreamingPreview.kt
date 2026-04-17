package com.hrm.markdown.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.markdown.renderer.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val streamingPreviewGroups = listOf(
    PreviewGroup(
        id = "streaming_demo",
        title = "流式渲染演示",
        description = "模拟 LLM 逐 token 输出，实时增量解析与渲染",
        items = listOf(
            PreviewItem(
                id = "streaming_full",
                title = "完整流式渲染",
                content = { StreamingMarkdownDemo() }
            ),
        )
    ),
)

@Composable
private fun StreamingMarkdownDemo() {
    var text by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var streamFinished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (!isRunning) {
                        text = ""
                        isRunning = true
                        streamFinished = false
                        scope.launch {
                            for (token in streamingTokens) {
                                text += token
                                delay(token.streamDelay())
                            }
                            isRunning = false
                            streamFinished = true
                        }
                    }
                },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isRunning) "生成中..."
                    else if (streamFinished) "重新生成"
                    else "开始流式生成"
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isRunning) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "模拟 LLM 逐 token 输出中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (streamFinished) {
                Text(
                    text = "生成完毕",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (text.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "点击「开始流式生成」按钮，模拟 LLM 逐 token 输出 Markdown\n\n" +
                            "Markdown 组件内置流式优化：\n" +
                            "• 自动节流渲染，避免高频更新导致的布局抖动\n" +
                            "• 流式期间跳过 SelectionContainer，减少 intrinsic 测量\n" +
                            "• 流式结束后自动恢复文本选择能力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp
                )
            }
        } else {
            Markdown(
                markdown = text,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                scrollState = scrollState,
                isStreaming = isRunning,
                onLinkClick = { url ->
                    println("Clicked link: $url")
                }
            )

            var autoFollow by remember { mutableStateOf(true) }

            LaunchedEffect(isRunning) {
                if (!isRunning) return@LaunchedEffect
                autoFollow = true

                launch {
                    snapshotFlow { scrollState.isScrollInProgress to scrollState.value }
                        .collect { (isScrolling, value) ->
                            if (isScrolling && value < scrollState.maxValue - 100) {
                                autoFollow = false
                            }
                        }
                }

                launch {
                    snapshotFlow { Triple(scrollState.value, scrollState.maxValue, autoFollow) }
                        .collect { (value, maxValue, follow) ->
                            if (!follow && maxValue > 0 && value >= maxValue - 100) {
                                autoFollow = true
                            }
                        }
                }

                launch {
                    snapshotFlow { scrollState.maxValue to autoFollow }
                        .collect { (maxValue, follow) ->
                            if (!follow) return@collect
                            withFrameNanos { }
                            if (kotlin.math.abs(scrollState.value - maxValue) > 6) {
                                scrollState.scrollTo(maxValue)
                            }
                        }
                }
            }

            LaunchedEffect(streamFinished) {
                if (!streamFinished) return@LaunchedEffect
                delay(50L)
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }
}

private fun String.streamDelay(): Long = when {
    this == "\n" || this == "\n\n" -> 30L
    this.startsWith("#") -> 15L
    this.startsWith("```") -> 20L
    this.length > 10 -> 10L
    else -> 25L
}

private val streamingTokens = listOf(
    // ═══ 标题 + 概述 ═══
    "# ",
    "Kotlin ",
    "Multiplatform ",
    "完全指南",
    "\n\n",
    "本文",
    "将全面介绍 ",
    "**Kotlin Multiplatform**（KMP）",
    "的核心概念、",
    "架构设计、",
    "最佳实践",
    "以及在",
    "生产环境中的",
    "实际应用。",
    "无论你是",
    "刚接触 KMP ",
    "的新手，",
    "还是经验丰富的",
    "跨平台开发者，",
    "都能从中",
    "获得有价值的",
    "参考。",
    "\n\n",

    // ═══ 第一章 ═══
    "## ",
    "1. ",
    "什么是 ",
    "Kotlin ",
    "Multiplatform",
    "\n\n",
    "Kotlin Multiplatform ",
    "是 JetBrains ",
    "推出的",
    "跨平台开发",
    "技术，",
    "允许在 ",
    "**Android**、",
    "**iOS**、",
    "**Desktop**、",
    "**Web** ",
    "等平台之间",
    "共享业务逻辑代码，",
    "同时保留",
    "各平台的",
    "原生 UI ",
    "开发体验。",
    "\n\n",

    "与其他",
    "跨平台方案",
    "（如 Flutter、",
    "React Native）",
    "不同，",
    "KMP ",
    "采用了",
    "***共享逻辑、",
    "原生 UI***",
    " 的哲学：",
    "\n\n",

    "- ",
    "**共享层**：",
    "网络请求、",
    "数据库操作、",
    "业务逻辑、",
    "状态管理",
    "\n",
    "- ",
    "**平台层**：",
    "各平台原生 UI ",
    "框架（",
    "Jetpack Compose、",
    "SwiftUI、",
    "Compose for Desktop）",
    "\n",
    "- ",
    "**桥接层**：",
    "`expect/actual` ",
    "机制实现",
    "平台特定功能",
    "\n\n",

    // ═══ 代码示例 1 ═══
    "### ",
    "1.1 ",
    "expect/actual ",
    "机制",
    "\n\n",
    "这是 KMP ",
    "最核心的",
    "特性之一。",
    "通过 `expect` ",
    "声明公共接口，",
    "通过 `actual` ",
    "提供平台实现：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "// commonMain - 公共声明\n",
    "expect class PlatformInfo() {\n",
    "    val name: String\n",
    "    val version: String\n",
    "    fun getDeviceId(): String\n",
    "}\n",
    "\n",
    "// androidMain - Android 实现\n",
    "actual class PlatformInfo actual constructor() {\n",
    "    actual val name: String = \"Android\"\n",
    "    actual val version: String = Build.VERSION.RELEASE\n",
    "    actual fun getDeviceId(): String {\n",
    "        return Settings.Secure.getString(\n",
    "            context.contentResolver,\n",
    "            Settings.Secure.ANDROID_ID\n",
    "        )\n",
    "    }\n",
    "}\n",
    "\n",
    "// iosMain - iOS 实现\n",
    "actual class PlatformInfo actual constructor() {\n",
    "    actual val name: String = \"iOS\"\n",
    "    actual val version: String = UIDevice.currentDevice.systemVersion\n",
    "    actual fun getDeviceId(): String {\n",
    "        return UIDevice.currentDevice\n",
    "            .identifierForVendor\n",
    "            ?.UUIDString ?: \"unknown\"\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第二章 ═══
    "## ",
    "2. ",
    "项目架构",
    "设计",
    "\n\n",
    "一个良好的 ",
    "KMP ",
    "项目架构",
    "是成功的关键。",
    "以下是推荐的",
    "模块化架构",
    "方案：",
    "\n\n",

    "### ",
    "2.1 ",
    "模块划分",
    "\n\n",
    "| 模块 | 职责 | 平台 | 依赖 |\n",
    "|:---|:---|:---:|:---|\n",
    "| `core-model` | 数据模型定义 | Common | 无 |\n",
    "| `core-network` | 网络层封装 | Common | Ktor |\n",
    "| `core-database` | 本地数据库 | Common | SQLDelight |\n",
    "| `core-domain` | 业务逻辑 | Common | core-model |\n",
    "| `feature-home` | 首页功能 | Common | core-domain |\n",
    "| `feature-search` | 搜索功能 | Common | core-domain |\n",
    "| `feature-profile` | 个人中心 | Common | core-domain |\n",
    "| `app-android` | Android 壳工程 | Android | feature-* |\n",
    "| `app-ios` | iOS 壳工程 | iOS | feature-* |\n",
    "| `app-desktop` | Desktop 壳工程 | JVM | feature-* |\n\n",

    "### ",
    "2.2 ",
    "依赖注入",
    "\n\n",
    "推荐使用 ",
    "**Koin** ",
    "作为 KMP ",
    "的依赖注入框架，",
    "因为它对",
    " Kotlin Multiplatform ",
    "有一流的支持：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "// 公共模块定义\n",
    "val commonModule = module {\n",
    "    single<UserRepository> { UserRepositoryImpl(get()) }\n",
    "    single<PostRepository> { PostRepositoryImpl(get()) }\n",
    "    factory { GetUserUseCase(get()) }\n",
    "    factory { GetPostsUseCase(get()) }\n",
    "    viewModel { HomeViewModel(get(), get()) }\n",
    "    viewModel { ProfileViewModel(get()) }\n",
    "}\n",
    "\n",
    "// 平台特定模块\n",
    "val platformModule = module {\n",
    "    single<DatabaseDriver> { createDatabaseDriver() }\n",
    "    single<HttpClient> { createHttpClient() }\n",
    "    single<SettingsStorage> { createSettings() }\n",
    "}\n",
    "\n",
    "// 初始化\n",
    "fun initKoin() {\n",
    "    startKoin {\n",
    "        modules(commonModule, platformModule)\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第三章 ═══
    "## ",
    "3. ",
    "网络层",
    "实践",
    "\n\n",
    "**Ktor** ",
    "是 KMP ",
    "生态中",
    "最成熟的",
    "网络库。",
    "以下展示",
    "如何构建",
    "一个健壮的",
    "网络层：",
    "\n\n",

    "### ",
    "3.1 ",
    "HttpClient ",
    "配置",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "class ApiClient(\n",
    "    private val httpClient: HttpClient,\n",
    "    private val tokenProvider: TokenProvider,\n",
    ") {\n",
    "    companion object {\n",
    "        private const val BASE_URL = \"https://api.example.com/v2\"\n",
    "        private const val TIMEOUT_MS = 30_000L\n",
    "    }\n",
    "\n",
    "    suspend inline fun <reified T> get(\n",
    "        path: String,\n",
    "        params: Map<String, String> = emptyMap(),\n",
    "    ): Result<T> = runCatching {\n",
    "        httpClient.get(\"\${'$'}BASE_URL\${'$'}path\") {\n",
    "            params.forEach { (key, value) ->\n",
    "                parameter(key, value)\n",
    "            }\n",
    "            header(\"Authorization\", \"Bearer \${'$'}{tokenProvider.getToken()}\")\n",
    "            contentType(ContentType.Application.Json)\n",
    "        }.body()\n",
    "    }\n",
    "\n",
    "    suspend inline fun <reified T, reified R> post(\n",
    "        path: String,\n",
    "        body: T,\n",
    "    ): Result<R> = runCatching {\n",
    "        httpClient.post(\"\${'$'}BASE_URL\${'$'}path\") {\n",
    "            header(\"Authorization\", \"Bearer \${'$'}{tokenProvider.getToken()}\")\n",
    "            contentType(ContentType.Application.Json)\n",
    "            setBody(body)\n",
    "        }.body()\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    "### ",
    "3.2 ",
    "错误处理",
    "策略",
    "\n\n",

    "网络请求",
    "的错误处理",
    "至关重要。",
    "建议采用",
    "**密封类**",
    "统一管理",
    "错误类型：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "sealed class NetworkError : Throwable() {\n",
    "    data class HttpError(\n",
    "        val code: Int,\n",
    "        override val message: String,\n",
    "    ) : NetworkError()\n",
    "\n",
    "    data class Timeout(\n",
    "        val url: String,\n",
    "        val timeoutMs: Long,\n",
    "    ) : NetworkError() {\n",
    "        override val message = \"Request to \${'$'}url timed out after \${'$'}{timeoutMs}ms\"\n",
    "    }\n",
    "\n",
    "    data object NoConnection : NetworkError() {\n",
    "        override val message = \"No internet connection\"\n",
    "    }\n",
    "\n",
    "    data class ParseError(\n",
    "        override val message: String,\n",
    "        override val cause: Throwable?,\n",
    "    ) : NetworkError()\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第四章 ═══
    "## ",
    "4. ",
    "数据库与",
    "本地存储",
    "\n\n",

    "### ",
    "4.1 ",
    "SQLDelight ",
    "实践",
    "\n\n",
    "SQLDelight ",
    "是 KMP ",
    "中最推荐的",
    "数据库方案，",
    "它通过 SQL ",
    "生成类型安全的 ",
    "Kotlin API：",
    "\n\n",

    "```",
    "sql",
    "\n",
    "-- User.sq\n",
    "CREATE TABLE user (\n",
    "    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n",
    "    username TEXT NOT NULL UNIQUE,\n",
    "    display_name TEXT NOT NULL,\n",
    "    avatar_url TEXT,\n",
    "    bio TEXT DEFAULT '',\n",
    "    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),\n",
    "    updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))\n",
    ");\n",
    "\n",
    "selectAll:\n",
    "SELECT * FROM user ORDER BY created_at DESC;\n",
    "\n",
    "selectById:\n",
    "SELECT * FROM user WHERE id = ?;\n",
    "\n",
    "searchByName:\n",
    "SELECT * FROM user\n",
    "WHERE username LIKE '%' || ? || '%'\n",
    "   OR display_name LIKE '%' || ? || '%'\n",
    "ORDER BY username ASC\n",
    "LIMIT ?;\n",
    "\n",
    "insert:\n",
    "INSERT OR REPLACE INTO user(username, display_name, avatar_url, bio)\n",
    "VALUES (?, ?, ?, ?);\n",
    "\n",
    "deleteById:\n",
    "DELETE FROM user WHERE id = ?;\n",
    "```",
    "\n\n",

    "### ",
    "4.2 ",
    "Repository ",
    "模式",
    "\n\n",

    "将网络层",
    "和数据库层",
    "统一封装在 ",
    "Repository ",
    "中：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "class UserRepositoryImpl(\n",
    "    private val api: ApiClient,\n",
    "    private val db: AppDatabase,\n",
    "    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,\n",
    ") : UserRepository {\n",
    "\n",
    "    override fun getUsers(): Flow<List<User>> {\n",
    "        return db.userQueries.selectAll()\n",
    "            .asFlow()\n",
    "            .mapToList(dispatcher)\n",
    "            .map { entities -> entities.map { it.toDomain() } }\n",
    "    }\n",
    "\n",
    "    override suspend fun refreshUsers(): Result<Unit> {\n",
    "        return api.get<List<UserDto>>(\"/users\")\n",
    "            .map { dtos ->\n",
    "                db.transaction {\n",
    "                    dtos.forEach { dto ->\n",
    "                        db.userQueries.insert(\n",
    "                            username = dto.username,\n",
    "                            display_name = dto.displayName,\n",
    "                            avatar_url = dto.avatarUrl,\n",
    "                            bio = dto.bio,\n",
    "                        )\n",
    "                    }\n",
    "                }\n",
    "            }\n",
    "    }\n",
    "\n",
    "    override suspend fun searchUsers(\n",
    "        query: String,\n",
    "        limit: Int,\n",
    "    ): List<User> {\n",
    "        return withContext(dispatcher) {\n",
    "            db.userQueries\n",
    "                .searchByName(query, query, limit.toLong())\n",
    "                .executeAsList()\n",
    "                .map { it.toDomain() }\n",
    "        }\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第五章 ═══
    "## ",
    "5. ",
    "状态管理",
    "与 UI ",
    "架构",
    "\n\n",

    "### ",
    "5.1 ",
    "MVI ",
    "架构",
    "\n\n",
    "推荐使用 ",
    "**MVI**（",
    "Model-View-Intent）",
    "架构模式，",
    "它具有",
    "单向数据流、",
    "状态不可变、",
    "易于测试",
    "等优点：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "// 状态定义\n",
    "data class HomeState(\n",
    "    val users: List<User> = emptyList(),\n",
    "    val isLoading: Boolean = false,\n",
    "    val error: String? = null,\n",
    "    val searchQuery: String = \"\",\n",
    "    val selectedTab: Tab = Tab.ALL,\n",
    ") {\n",
    "    enum class Tab { ALL, FAVORITES, RECENT }\n",
    "}\n",
    "\n",
    "// 意图定义\n",
    "sealed interface HomeIntent {\n",
    "    data object LoadUsers : HomeIntent\n",
    "    data object RefreshUsers : HomeIntent\n",
    "    data class Search(val query: String) : HomeIntent\n",
    "    data class SelectTab(val tab: HomeState.Tab) : HomeIntent\n",
    "    data class ToggleFavorite(val userId: Long) : HomeIntent\n",
    "}\n",
    "\n",
    "// ViewModel\n",
    "class HomeViewModel(\n",
    "    private val getUsersUseCase: GetUsersUseCase,\n",
    "    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,\n",
    ") : ViewModel() {\n",
    "\n",
    "    private val _state = MutableStateFlow(HomeState())\n",
    "    val state: StateFlow<HomeState> = _state.asStateFlow()\n",
    "\n",
    "    fun onIntent(intent: HomeIntent) {\n",
    "        when (intent) {\n",
    "            is HomeIntent.LoadUsers -> loadUsers()\n",
    "            is HomeIntent.RefreshUsers -> refreshUsers()\n",
    "            is HomeIntent.Search -> search(intent.query)\n",
    "            is HomeIntent.SelectTab -> selectTab(intent.tab)\n",
    "            is HomeIntent.ToggleFavorite -> toggleFavorite(intent.userId)\n",
    "        }\n",
    "    }\n",
    "\n",
    "    private fun loadUsers() {\n",
    "        viewModelScope.launch {\n",
    "            _state.update { it.copy(isLoading = true, error = null) }\n",
    "            getUsersUseCase()\n",
    "                .catch { e -> _state.update { it.copy(error = e.message) } }\n",
    "                .collect { users -> _state.update { it.copy(users = users, isLoading = false) } }\n",
    "        }\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第六章 ═══
    "## ",
    "6. ",
    "性能优化",
    "\n\n",

    "在 KMP ",
    "项目中，",
    "性能优化",
    "是不可忽视的",
    "环节。",
    "以下是一些",
    "关键的",
    "优化策略：",
    "\n\n",

    "### ",
    "6.1 ",
    "协程优化",
    "\n\n",

    "1. ",
    "**使用合适的 Dispatcher**",
    "：IO 操作用 `Dispatchers.IO`，",
    "计算密集用 `Dispatchers.Default`",
    "\n",
    "2. ",
    "**避免不必要的协程切换**",
    "：多个挂起调用",
    "尽量合并在同一个作用域",
    "\n",
    "3. ",
    "**使用 `SupervisorJob`**",
    "：隔离子协程失败，",
    "防止级联取消",
    "\n",
    "4. ",
    "**合理使用 `Flow` 操作符**",
    "：`distinctUntilChanged()` ",
    "避免重复发射，",
    "`debounce()` ",
    "防止高频操作",
    "\n\n",

    "### ",
    "6.2 ",
    "内存优化",
    "\n\n",

    "> [!WARNING]\n",
    "> 在 iOS ",
    "平台上需要",
    "特别注意内存管理。",
    "Kotlin/Native ",
    "使用的是",
    "引用计数 + ",
    "循环检测的",
    "GC 策略，",
    "与 JVM 的 GC ",
    "行为不同。",
    "\n\n",

    "关键注意事项：",
    "\n\n",

    "- ",
    "避免持有",
    "大型对象的",
    "强引用",
    "\n",
    "- ",
    "使用 `WeakReference` ",
    "处理",
    "可能的循环引用",
    "\n",
    "- ",
    "在 `ViewModel` ",
    "的 `onCleared()` ",
    "中释放资源",
    "\n",
    "- ",
    "使用 `remember` + `derivedStateOf` ",
    "减少 Compose ",
    "不必要的重组",
    "\n",
    "- ",
    "图片加载",
    "使用 `Coil` ",
    "并配置合理的",
    "缓存策略",
    "\n\n",

    "### ",
    "6.3 ",
    "编译优化",
    "\n\n",

    "| 优化项 | 配置 | 效果 |\n",
    "|:---|:---|:---|\n",
    "| Gradle 构建缓存 | `org.gradle.caching=true` | 增量编译加速 30-50% |\n",
    "| 并行编译 | `org.gradle.parallel=true` | 多模块并行编译 |\n",
    "| Configuration Cache | `org.gradle.configuration-cache=true` | 配置阶段缓存 |\n",
    "| Kotlin 增量编译 | `kotlin.incremental=true` | Kotlin 增量编译 |\n",
    "| K2 编译器 | `kotlin.experimental.tryK2=true` | 编译速度提升 2x |\n",
    "| iOS Framework | `isStatic = true` | 减少 iOS 包体积 |\n\n",

    // ═══ 第七章 ═══
    "## ",
    "7. ",
    "测试策略",
    "\n\n",

    "### ",
    "7.1 ",
    "测试金字塔",
    "\n\n",

    "```",
    "\n",
    "        /  E2E Tests  \\           <- 少量 (UI 自动化)\n",
    "       / Integration    \\         <- 中等 (API + DB)\n",
    "      /   Unit Tests     \\        <- 大量 (纯逻辑)\n",
    "     /____________________\\       <- 共享测试代码\n",
    "```",
    "\n\n",

    "### ",
    "7.2 ",
    "共享测试",
    "\n\n",
    "KMP ",
    "的一大优势",
    "是**测试代码",
    "也可以共享**。",
    "在 `commonTest` ",
    "中编写的",
    "单元测试",
    "可以在",
    "所有平台上",
    "运行：",
    "\n\n",

    "```",
    "kotlin",
    "\n",
    "class UserRepositoryTest {\n",
    "\n",
    "    private val fakeApi = FakeApiClient()\n",
    "    private val fakeDb = FakeDatabase()\n",
    "    private val repository = UserRepositoryImpl(fakeApi, fakeDb)\n",
    "\n",
    "    @Test\n",
    "    fun should_return_cached_users_when_db_has_data() = runTest {\n",
    "        // Arrange\n",
    "        fakeDb.insertUser(testUser(id = 1, name = \"Alice\"))\n",
    "        fakeDb.insertUser(testUser(id = 2, name = \"Bob\"))\n",
    "\n",
    "        // Act\n",
    "        val users = repository.getUsers().first()\n",
    "\n",
    "        // Assert\n",
    "        assertEquals(2, users.size)\n",
    "        assertEquals(\"Alice\", users[0].name)\n",
    "        assertEquals(\"Bob\", users[1].name)\n",
    "    }\n",
    "\n",
    "    @Test\n",
    "    fun should_refresh_and_cache_users_from_api() = runTest {\n",
    "        // Arrange\n",
    "        fakeApi.setResponse(\n",
    "            listOf(\n",
    "                UserDto(\"charlie\", \"Charlie\", null, \"Hello!\"),\n",
    "                UserDto(\"diana\", \"Diana\", \"https://img.com/diana.jpg\", \"Dev\"),\n",
    "            )\n",
    "        )\n",
    "\n",
    "        // Act\n",
    "        val result = repository.refreshUsers()\n",
    "\n",
    "        // Assert\n",
    "        assertTrue(result.isSuccess)\n",
    "        val cached = fakeDb.getAllUsers()\n",
    "        assertEquals(2, cached.size)\n",
    "        assertEquals(\"Charlie\", cached[0].displayName)\n",
    "    }\n",
    "\n",
    "    @Test\n",
    "    fun should_handle_network_error_gracefully() = runTest {\n",
    "        // Arrange\n",
    "        fakeApi.setError(NetworkError.NoConnection)\n",
    "\n",
    "        // Act\n",
    "        val result = repository.refreshUsers()\n",
    "\n",
    "        // Assert\n",
    "        assertTrue(result.isFailure)\n",
    "        assertTrue(result.exceptionOrNull() is NetworkError.NoConnection)\n",
    "    }\n",
    "}\n",
    "```",
    "\n\n",

    // ═══ 第八章 ═══
    "## ",
    "8. ",
    "CI/CD ",
    "与发布",
    "\n\n",

    "### ",
    "8.1 ",
    "推荐的 ",
    "CI/CD ",
    "流水线",
    "\n\n",

    "```",
    "yaml",
    "\n",
    "# .github/workflows/ci.yml\n",
    "name: KMP CI/CD Pipeline\n",
    "\n",
    "on:\n",
    "  push:\n",
    "    branches: [main, develop]\n",
    "  pull_request:\n",
    "    branches: [main]\n",
    "\n",
    "jobs:\n",
    "  test:\n",
    "    strategy:\n",
    "      matrix:\n",
    "        os: [ubuntu-latest, macos-latest]\n",
    "    runs-on: \${'$'}{{ matrix.os }}\n",
    "    steps:\n",
    "      - uses: actions/checkout@v4\n",
    "      - uses: actions/setup-java@v4\n",
    "        with:\n",
    "          java-version: '17'\n",
    "          distribution: 'temurin'\n",
    "      - name: Run Tests\n",
    "        run: ./gradlew allTests --no-daemon\n",
    "      - name: Upload Reports\n",
    "        uses: actions/upload-artifact@v4\n",
    "        with:\n",
    "          name: test-reports-\${'$'}{{ matrix.os }}\n",
    "          path: '**/build/reports/tests/'\n",
    "\n",
    "  build-android:\n",
    "    needs: test\n",
    "    runs-on: ubuntu-latest\n",
    "    steps:\n",
    "      - uses: actions/checkout@v4\n",
    "      - name: Build APK\n",
    "        run: ./gradlew :app-android:assembleRelease\n",
    "      - name: Upload APK\n",
    "        uses: actions/upload-artifact@v4\n",
    "        with:\n",
    "          name: android-release\n",
    "          path: app-android/build/outputs/apk/release/\n",
    "\n",
    "  build-ios:\n",
    "    needs: test\n",
    "    runs-on: macos-latest\n",
    "    steps:\n",
    "      - uses: actions/checkout@v4\n",
    "      - name: Build XCFramework\n",
    "        run: ./gradlew :shared:assembleXCFramework\n",
    "```",
    "\n\n",

    // ═══ 总结 ═══
    "## ",
    "9. ",
    "总结",
    "\n\n",

    "Kotlin Multiplatform ",
    "已经从",
    "实验性功能",
    "发展为",
    "生产就绪的",
    "跨平台",
    "解决方案。",
    "它的核心优势",
    "在于：",
    "\n\n",

    "1. ",
    "**渐进式采用**",
    "：可以从",
    "一个小模块开始，",
    "逐步扩大",
    "共享范围",
    "\n",
    "2. ",
    "**原生体验**",
    "：每个平台",
    "都使用",
    "原生 UI ",
    "框架",
    "\n",
    "3. ",
    "**代码质量**",
    "：共享的",
    "业务逻辑",
    "只需要",
    "编写和",
    "测试一次",
    "\n",
    "4. ",
    "**团队效率**",
    "：减少",
    "多平台之间的",
    "重复开发",
    "和不一致性",
    "\n",
    "5. ",
    "**生态成熟**",
    "：Ktor、",
    "SQLDelight、",
    "Koin、",
    "Compose Multiplatform ",
    "等库",
    "已经非常稳定",
    "\n\n",

    "> [!TIP]\n",
    "> 如果你正在",
    "考虑跨平台方案，",
    "KMP ",
    "是一个",
    "值得认真评估的",
    "选择。",
    "特别是",
    "如果你的团队",
    "已经熟悉 Kotlin，",
    "那么迁移成本",
    "将非常低。",
    "\n\n",

    "---",
    "\n\n",
    "*本文",
    "由流式渲染",
    "引擎实时",
    "生成。",
    "Powered by ",
    "`StreamingParser` + ",
    "`IncrementalEngine`*"
)
