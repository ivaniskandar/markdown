---
name: add-inline-syntax
description: "This skill provides the complete workflow for adding a new inline-level Markdown syntax to the KMP Markdown library. It covers AST node definition, InlineParser modification, InlineRenderer update, unit tests, preview examples, and coverage doc update. This skill should be used when the user wants to add support for a new inline element such as a new text decoration, annotation, or inline component to the parser and renderer."
---

# Add Inline-Level Syntax

This skill implements the end-to-end workflow for adding a new inline-level Markdown syntax feature across all modules: parser → renderer → preview → tests → docs.

## Background: Inline Parsing Architecture

The `InlineParser` (~1470 lines) uses the CommonMark delimiter algorithm:
- Maintains a **doubly-linked list** of text/delimiter nodes
- Uses a **delimiter stack** (`DelimEntry`) for emphasis-like syntax
- Uses a **bracket stack** (`BracketEntry`) for links/images
- `processEmphasis()` matches opening/closing delimiters to create inline AST nodes

There are two categories of inline syntax:

1. **Delimiter-based** (emphasis, strikethrough, highlight, super/subscript, inserted text) — uses the delimiter stack, matched in `processEmphasis()`
2. **Direct-scan** (inline code, autolinks, HTML entities, math, emoji, escapes) — detected character-by-character in the main scan loop

## Workflow

### Step 1: Define AST Node

**File**: `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/ast/InlineNodes.kt`

**Container inline** (wraps children, e.g., emphasis, highlight):
```kotlin
class MyInline(
    var delimiter: Char = ' ',
) : ContainerNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitMyInline(this)
}
```

**Leaf inline** (no children, e.g., inline code, emoji):
```kotlin
class MyInlineLeaf(
    override var literal: String = "",
) : LeafNode() {
    override fun <R> accept(visitor: NodeVisitor<R>): R = visitor.visitMyInlineLeaf(this)
}
```

Also add the visitor method to `NodeVisitor` interface and `DefaultNodeVisitor` in `ast/NodeVisitor.kt`.

### Step 2: Update InlineParser

**File**: `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/inline/InlineParser.kt`

#### For delimiter-based syntax (e.g., `@@text@@`):

1. In the **main scan loop** (the large `when` on current character), add a case for the delimiter character:
```kotlin
'@' -> {
    if (enableExtendedInline) {
        handleDelimRun('@', minCount = 2)  // or custom handling
    } else {
        appendText(c)
    }
}
```

2. In **`processEmphasis()`**, add a branch to create the node when matching delimiters are found:
```kotlin
'@' -> {
    val node = MyInline(delimiter = '@')
    // Move nodes between opener and closer into node.children
    wrapNodes(opener, closer, node)
}
```

3. The syntax is gated by `enableExtendedInline` from the Flavour — set `true` in `ExtendedFlavour` (already default true).

#### For direct-scan syntax (e.g., `:shortcode:`):

In the main scan loop, handle the trigger character and scan ahead:
```kotlin
':' -> {
    val result = tryParseMyInline(pos)
    if (result != null) {
        appendNode(result.node)
        pos = result.endPos
    } else {
        appendText(c)
    }
}
```

Implement `tryParseMyInline()` as a private method that returns the parsed node and end position, or null on failure.

### Step 3: Update InlineRenderer

**File**: `markdown-renderer/src/commonMain/kotlin/com/hrm/markdown/renderer/inline/InlineRenderer.kt`

Add a branch in the `renderInlineNode()` function's `when (node)` expression:

**For styled container** (applies SpanStyle to children):
```kotlin
is MyInline -> {
    withStyle(SpanStyle(/* style from theme */)) {
        renderInlineChildren(node.children, theme, onLinkClick, inlineContents, density, imageMeasurer, latexMeasurer)
    }
}
```

**For leaf with text** (renders literal):
```kotlin
is MyInlineLeaf -> {
    withStyle(theme.myInlineStyle) {
        append(node.literal)
    }
}
```

**For non-text inline content** (e.g., images, math — needs `InlineTextContent`):
```kotlin
is MyInlineLeaf -> {
    val id = "my_inline_${node.hashCode()}"
    appendInlineContent(id, node.literal)
    inlineContents[id] = InlineTextContent(
        placeholder = Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)
    ) {
        // @Composable rendering lambda
    }
}
```

If the new syntax needs theme properties, add them to `MarkdownTheme` in `markdown-renderer/src/commonMain/kotlin/com/hrm/markdown/renderer/MarkdownTheme.kt`. Follow the existing pattern of adding a property with defaults for both `light()` and `dark()` factory methods.

### Step 4: Handle Streaming Auto-Close (if delimiter-based)

**File**: `markdown-parser/src/commonMain/kotlin/com/hrm/markdown/parser/streaming/InlineAutoCloser.kt`

For delimiter-based syntax, add auto-close logic so that unclosed delimiters during LLM streaming are automatically completed:

```kotlin
// In the delimiter tracking section, add the new delimiter character
'@' -> pendingDelimiters.add("@@")
```

This ensures that partial streaming output like `@@bold text` renders correctly as `@@bold text@@` until more tokens arrive.

### Step 5: Write Unit Tests

**File**: Create `markdown-parser/src/commonTest/kotlin/com/hrm/markdown/parser/MyInlineTest.kt`

```kotlin
package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MyInlineTest {
    private val parser = MarkdownParser()

    @Test
    fun should_parse_basic_my_inline() {
        val doc = parser.parse("text @@highlighted@@ text")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        val inline = (para as Paragraph).children[1]
        assertIs<MyInline>(inline)
    }

    @Test
    fun should_parse_nested_in_emphasis() {
        val doc = parser.parse("**@@bold highlighted@@**")
        // Verify nesting structure
    }

    @Test
    fun should_not_parse_single_delimiter() {
        val doc = parser.parse("text @not inline@ text")
        val para = doc.children.first()
        assertIs<Paragraph>(para)
        // Should be plain text, not MyInline
    }
}
```

Required coverage: basic parsing, nesting with other inline elements, invalid/incomplete delimiters, edge cases (empty content, adjacent delimiters, CJK context).

Run tests: `./gradlew :markdown-parser:jvmTest`

### Step 6: Add Preview and Update Docs

**Preview**: Add preview items in `markdown-preview/src/commonMain/kotlin/com/hrm/markdown/preview/`. For inline syntax, typically add to `TextStylePreview.kt` or `ExtendedPreview.kt` depending on the feature category.

**Coverage doc**: Update `markdown-parser/PARSER_COVERAGE_ANALYSIS.md` — mark the new syntax as ✅.

### Verification

```bash
./gradlew jvmTest
```

## CJK Considerations

When implementing delimiter-based inline syntax, be aware that the `InlineParser` has special handling for CJK characters:
- Full-width punctuation is treated as punctuation in flanking delimiter rules
- Full-width spaces affect left/right flanking classification
- The `CharacterUtils` class in `core/CharacterUtils.kt` provides `isUnicodePunctuation()` and CJK-aware character classification

Ensure the new syntax works correctly with Chinese/Japanese/Korean text by including CJK test cases.
