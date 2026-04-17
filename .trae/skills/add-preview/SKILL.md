---
name: add-preview
description: "This skill provides the workflow for adding preview/demo examples to the markdown-preview module. It covers adding items to existing categories, creating new categories, and using both pure-Markdown and custom-Composable preview modes. This skill should be used when the user wants to add demonstration examples for Markdown features in the Demo app."
---

# Add Preview Examples

This skill implements the workflow for adding preview/demo examples to the `markdown-preview` module, which provides the categorized demo content displayed in the Demo app.

## Data Model

The preview system uses a three-level hierarchy:

```
PreviewCategory (id, title, description, icon, groups)
  └── PreviewGroup (id, title, description, items)
        └── PreviewItem (id, title, markdown?, content: @Composable)
```

All categories are registered in the top-level `previewCategories` list in:
`markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/MarkdownPreview.kt`

## Existing Categories

| ID | Title | File |
|---|---|---|
| `streaming` | 流式渲染 | `StreamingPreview.kt` |
| `text_styles` | 文本样式 | `TextStylePreview.kt` |
| `headings` | 标题 | `HeadingPreview.kt` |
| `lists` | 列表 | `ListPreview.kt` |
| `code` | 代码块 | `CodeBlockPreview.kt` |
| `table` | 表格 | `TablePreview.kt` |
| `blockquote` | 引用与 Admonition | `BlockquotePreview.kt` |
| `math` | 数学公式 | `MathPreview.kt` |
| `links_images` | 链接与图片 | `LinkImagePreview.kt` |
| `extended` | 扩展语法 | `ExtendedPreview.kt` |
| `diagram` | 图表 | `DiagramPreview.kt` |
| `pagination` | 分页加载 | `PaginationPreview.kt` |
| `linting` | 语法验证/Linting | `LintingPreview.kt` |
| `cjk` | 中文本地化优化 | `LintingPreview.kt` |

All files are in: `markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/`

## Workflow A: Add Items to Existing Category

1. Open the corresponding `*Preview.kt` file from the table above.
2. Find the appropriate `PreviewGroup` within the file, or create a new group.
3. Add a `PreviewItem` to the group's `items` list:

```kotlin
PreviewItem(
    id = "unique_item_id",
    title = "示例标题",
    content = {
        Markdown(
            markdown = """
                # Example
                
                This is **bold** and *italic* text.
            """.trimIndent()
        )
    }
)
```

For items needing theme or streaming configuration:
```kotlin
PreviewItem(
    id = "themed_item",
    title = "Dark Theme Example",
    content = {
        Markdown(
            markdown = "# Dark Mode",
            theme = MarkdownTheme.dark(),
        )
    }
)
```

## Workflow B: Create a New Category

### Step 1: Create Preview Data File

Create a new file in `markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/`:

```kotlin
package com.hrm.markdown.preview

import androidx.compose.runtime.Composable
import com.hrm.markdown.renderer.Markdown

internal val myFeaturePreviewGroups = listOf(
    PreviewGroup(
        id = "basic_usage",
        title = "基础用法",
        description = "基础示例",
        items = listOf(
            PreviewItem(
                id = "basic_1",
                title = "基础示例 1",
                content = {
                    Markdown(markdown = "example markdown content")
                }
            ),
        )
    ),
    PreviewGroup(
        id = "advanced_usage",
        title = "高级用法",
        description = "高级示例",
        items = listOf(
            PreviewItem(
                id = "advanced_1",
                title = "高级示例 1",
                content = {
                    Markdown(markdown = "advanced example")
                }
            ),
        )
    ),
)
```

### Step 2: Register in previewCategories

Edit `markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/MarkdownPreview.kt`, add to the `previewCategories` list:

```kotlin
PreviewCategory(
    id = "my_feature",
    title = "我的功能",
    description = "功能描述",
    icon = "🆕",
    groups = myFeaturePreviewGroups
),
```

No changes to `composeApp` are needed — `App.kt` calls `MarkdownPreview()` which automatically reads `previewCategories`.

## Custom Composable Previews

For features requiring interactive demos (streaming, pagination, etc.), use a custom `@Composable` in the `content` lambda instead of a simple `Markdown()` call:

```kotlin
PreviewItem(
    id = "interactive_demo",
    title = "交互式演示",
    content = {
        MyCustomDemo()  // Defined as a @Composable function in the same file
    }
)
```

Define the custom Composable in the same preview file with `internal` visibility.

## Verification

Run the Desktop Demo to visually verify the preview:
```bash
./gradlew :composeApp:run
```

Navigate to the new category/group/item in the three-level navigation UI to confirm rendering.
