package com.hrm.markdown.parser.ast

import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.LineRange

/**
 * 所有 AST 节点的基类。
 * 节点通过父子关系形成树结构。
 *
 * Tree-sitter 风格增量解析支持：
 * - 每个节点携带 [contentHash]，用于快速判断内容是否改变。
 * - 增量解析时，若旧节点与新源文本对应区域的哈希一致，
 *   则直接复用旧子树，无需重新解析。
 */
sealed class Node {
    var sourceRange: SourceRange = SourceRange.EMPTY
    var lineRange: LineRange = LineRange(0, 0)
    var parent: Node? = null

    /**
     * 内容哈希：基于该节点所覆盖的源文本行内容计算。
     * 用于增量解析时快速判断节点是否可复用。
     */
    var contentHash: Long = 0L

    /**
     * 接受访问者进行树遍历。
     */
    abstract fun <R> accept(visitor: NodeVisitor<R>): R
}

/**
 * 容器节点，可以包含子节点。
 */
sealed class ContainerNode : Node() {
    private val _children: MutableList<Node> = mutableListOf()
    val children: List<Node> get() = _children

    fun appendChild(child: Node) {
        child.parent = this
        _children.add(child)
    }

    fun insertChild(index: Int, child: Node) {
        child.parent = this
        _children.add(index, child)
    }

    fun removeChild(child: Node): Boolean {
        child.parent = null
        return _children.remove(child)
    }

    fun removeChildAt(index: Int): Node {
        val child = _children.removeAt(index)
        child.parent = null
        return child
    }

    fun replaceChild(old: Node, new: Node) {
        val index = _children.indexOf(old)
        if (index >= 0) {
            old.parent = null
            new.parent = this
            _children[index] = new
        }
    }

    fun replaceChildren(startIndex: Int, endIndex: Int, newChildren: List<Node>) {
        for (i in startIndex until endIndex) {
            _children[i].parent = null
        }
        val removed = endIndex - startIndex
        for (i in 0 until removed) {
            _children.removeAt(startIndex)
        }
        newChildren.forEachIndexed { i, child ->
            child.parent = this
            _children.add(startIndex + i, child)
        }
    }

    fun clearChildren() {
        _children.forEach { it.parent = null }
        _children.clear()
    }

    fun childCount(): Int = _children.size
}

/**
 * 叶子节点，不能包含子节点，仅包含文本内容。
 */
sealed class LeafNode : Node() {
    abstract val literal: String
}
