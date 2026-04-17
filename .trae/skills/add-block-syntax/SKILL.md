---
name: add-block-syntax
description: "This skill provides the complete workflow for adding a new block-level Markdown syntax to the KMP Markdown library. It covers AST node definition, BlockStarter implementation, Flavour registration, block renderer creation, BlockRenderer dispatcher update, unit tests, preview examples, and coverage doc update. This skill should be used when the user wants to add support for a new block-level element such as a new type of code block, container, or leaf block to the parser and renderer."
---

# Add Block-Level Syntax

This skill implements the end-to-end workflow for adding a new block-level Markdown syntax feature across all modules: parser → renderer → preview → tests → docs.

## Workflow

Execute steps 1–8 in order. Each step specifies the exact file path and the pattern to follow.

### Step 1: Define AST Node

**File**: `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/ast/BlockNodes.kt`

Determine whether the new block is a **container** (can hold child nodes) or a **leaf** (holds literal text only).

**Leaf node pattern** (e.g., code block, math block):
```kotlin
class MyBlock(
    override var literal: String = "",
    var myCustomField: String = "",
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitMyBlock(this)
}
```

**Container node pattern** (e.g., admonition, custom container):
```kotlin
class MyContainer(
    var type: String = "",
    var title: String = "",
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitMyContainer(this)
}
```

Also add the `visitMyBlock`/`visitMyContainer` method to `NodeVisitor` interface and `DefaultNodeVisitor` in `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/ast/NodeVisitor.kt`.

### Step 2: Implement BlockStarter

**File**: Create `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/block/starters/MyBlockStarter.kt`

Follow this pattern:
```kotlin
package com.hrm.markdown.parser.block.starters

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.ast.MyBlock
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.core.LineCursor

internal class MyBlockStarter : BlockStarter {
    // Priority ranges: 0-99 highest, 100-199 headings, 200-299 tables/breaks,
    // 300-399 fenced/containers/math, 400-499 HTML/quotes, 500-599 lists, 600+ lowest
    override val priority: Int = 350
    override val canInterruptParagraph: Boolean = true

    override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        val indent = cursor.advanceSpaces(3)  // allow up to 3 leading spaces
        // Character-level matching logic using cursor.peek(), cursor.advance(), cursor.rest()
        // Return null if no match
        // On match: create AST node, set lineRange, wrap in OpenBlock, set starterTag
        val block = MyBlock(literal = "...")
        block.lineRange = LineRange(lineIdx, lineIdx + 1)
        val ob = OpenBlock(block, lastLineIndex = lineIdx)
        ob.starterTag = this::class.simpleName
        return ob
    }
}
```

Key points:
- `cursor.advanceSpaces(3)` consumes up to 3 leading spaces (CommonMark indentation rule)
- `cursor.peek()` / `cursor.peek(N)` for lookahead without consuming
- `cursor.advance()` consumes one character
- `cursor.rest()` returns remaining line content
- Return `null` on no match — the registry auto-restores cursor via snapshot/restore
- For multi-line blocks (e.g., fenced), set `contentStartLine` in `OpenBlock` and accumulate lines via `ob.contentLines`

### Step 3: (Optional) Implement PostProcessor

**File**: Create `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/block/postprocessors/MyPostProcessor.kt`

Only needed if the block requires AST transformations after initial parsing (e.g., converting a generic block into a specialized type, extracting metadata).

```kotlin
package com.hrm.markdown.parser.block.postprocessors

import com.hrm.markdown.parser.ast.*

class MyPostProcessor : PostProcessor {
    override val priority: Int = 350  // 100=heading IDs, 150=attributes, 200=abbreviations, 300=diagrams, 350=columns, 400=HTML filter
    override fun process(document: Document) {
        for (child in document.children) {
            processRecursive(child)
        }
    }
    private fun processRecursive(node: Node) {
        when (node) {
            is MyBlock -> { /* transform */ }
            is ContainerNode -> node.children.forEach { processRecursive(it) }
            else -> {}
        }
    }
}
```

### Step 4: Register in Flavour

**File**: `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/flavour/ExtendedFlavour.kt`

Add the new starter to `blockStarters` list, maintaining priority order:
```kotlin
override val blockStarters: List<BlockStarter> = listOf(
    // ... existing starters sorted by priority ...
    MyBlockStarter(),            // priority: 350
    // ...
)
```

If a PostProcessor was created, add it to `postProcessors` list similarly.

Decide which Flavour to register in:
- `CommonMarkFlavour` — only for spec-mandated CommonMark blocks
- `GFMFlavour` — for GFM extensions (tables, etc.)
- `ExtendedFlavour` — for all other extensions (most common)

### Step 5: Create Block Renderer

**File**: Create `markdown-renderer/src/commonMain/kotlin/com/hrm/markdown/renderer/block/MyBlockRenderer.kt`

```kotlin
package com.hrm.markdown.renderer.block

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hrm.markdown.parser.ast.MyBlock
import com.hrm.markdown.renderer.LocalMarkdownTheme

@Composable
internal fun MyBlockRenderer(
    node: MyBlock,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMarkdownTheme.current
    // Build Compose UI using theme properties
    // For containers: iterate node.children and call BlockRenderer(child) recursively
    // For leaves: render node.literal content
}
```

Access theme properties via `LocalMarkdownTheme.current`. If the new block needs custom theme properties, add them to `MarkdownTheme` data class in `markdown-renderer/src/commonMain/kotlin/com/hrm/markdown/renderer/MarkdownTheme.kt`.

### Step 6: Register in BlockRenderer Dispatcher

**File**: `markdown-renderer/src/commonMain/kotlin/com/hrm/markdown/renderer/block/BlockRenderer.kt`

Add a new branch to the `when (node)` expression:
```kotlin
is MyBlock -> MyBlockRenderer(node, modifier)
```

### Step 7: Write Unit Tests

**File**: Create `markdown-parser/src/commonTest/kotlin/com/hrm/markdown/parser/MyBlockTest.kt`

```kotlin
package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MyBlockTest {
    private val parser = MarkdownParser()

    @Test
    fun should_parse_basic_my_block() {
        val doc = parser.parse("<<< my block content >>>")
        val node = doc.children.first()
        assertIs<MyBlock>(node)
        assertEquals("my block content", node.literal)
    }

    @Test
    fun should_handle_empty_my_block() {
        val doc = parser.parse("<<<>>>")
        val node = doc.children.first()
        assertIs<MyBlock>(node)
        assertEquals("", node.literal)
    }

    @Test
    fun should_not_parse_invalid_my_block() {
        val doc = parser.parse("<< not enough brackets")
        val node = doc.children.first()
        assertIs<Paragraph>(node)  // falls through to paragraph
    }
}
```

Required coverage: happy path, edge cases (empty, special chars, nesting), error handling (invalid input degrades to paragraph).

Run tests: `./gradlew :markdown-parser:jvmTest`

### Step 8: Add Preview and Update Docs

**Preview**: Add preview items in `markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/`. Either add to an existing `*Preview.kt` or create a new file with `List<PreviewGroup>` and register in `previewCategories` in `MarkdownPreview.kt`.

**Coverage doc**: Update `markdown-parser/PARSER_COVERAGE_ANALYSIS.md` — mark the new syntax as ✅ supported, update coverage percentages.

### Verification

Run full test suite and confirm BUILD SUCCESSFUL:
```bash
./gradlew jvmTest
```

## Handling Multi-line (Fenced) Blocks

For blocks spanning multiple lines (like fenced code blocks or math blocks), the `BlockParser` uses continuation logic:

1. In `tryStart()`: Create `OpenBlock` with `contentStartLine` set, return the open block
2. The `BlockParser` will call `continueBlock()` for subsequent lines — for fenced blocks, check if the current line is a closing fence
3. Lines between open and close are accumulated in `ob.contentLines`
4. On `finalizeBlock()`, join content lines into `node.literal`

Refer to `FencedCodeBlockStarter.kt` and the `continueBlock()`/`finalizeBlock()` logic in `BlockParser.kt` for the exact pattern.
