package com.scluzlep.searchcx.ui

import com.scluzlep.searchcx.overlay.ImageCropUtils
import com.scluzlep.searchcx.overlay.NormalizedCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun splitsThinkTagsCleanlyIncludingUnclosedStreamingTag() {
        val (r1, b1) = MarkdownParser.splitThinkTags("<think>step 1\nstep 2</think>\nFinal answer")
        assertEquals("step 1\nstep 2", r1)
        assertEquals("Final answer", b1)

        val (r2, b2) = MarkdownParser.splitThinkTags("<think>still thinking...")
        assertEquals("still thinking...", r2)
        assertEquals("", b2)
    }

    @Test
    fun parsesHeadingsCodeTablesMathAndLists() {
        val md = """
            ## 解题思路
            考虑公式 ${'$'}\frac{x^2 + 1}{\sqrt{y}} \le \infty${'$'} 与 **重要结论**。

            $$
            \sum_{n=1}^{\infty} \frac{1}{n^2} = \frac{\pi^2}{6}
            $$

            | 变量 | 含义 |
            | --- | --- |
            | x | 输入 |

            ```kotlin
            fun add(a: Int, b: Int) = a + b
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        val para = blocks[1] as MdBlock.Paragraph
        assertTrue(para.inlines.any { it is MdInline.InlineMath && it.pretty.contains("√(y)") && it.pretty.contains("≤") })
        assertTrue(para.inlines.any { it is MdInline.Bold && it.text == "重要结论" })

        val mathBlock = blocks[2] as MdBlock.MathBlock
        assertTrue(mathBlock.pretty.contains("∑"))
        assertTrue(mathBlock.pretty.contains("π"))

        val table = blocks[3] as MdBlock.Table
        assertEquals(listOf("变量", "含义"), table.headers)
        assertEquals(listOf(listOf("x", "输入")), table.rows)

        val code = blocks[4] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals(false, code.closed)
        assertTrue(code.code.contains("fun add"))
    }

    @Test
    fun clampsCropBoundsSafely() {
        val clamped = ImageCropUtils.clampRect(NormalizedCropRect(-0.2f, 0.99f, 0.1f, 1.5f), minSpan = 0.06f)
        assertEquals(0f, clamped.left, 0.0001f)
        assertTrue(clamped.right >= clamped.left + 0.06f)
        assertTrue(clamped.bottom <= 1.0f)

        val px = ImageCropUtils.toPixelBounds(NormalizedCropRect(0.1f, 0.2f, 0.5f, 0.6f), 1000, 2000)
        assertEquals(100, px.left)
        assertEquals(400, px.top)
        assertEquals(400, px.width)
        assertEquals(800, px.height)
    }

    @Test
    fun parsesNestedFractionsBinomialsAndRadicalsIntoAst() {
        val nested = MarkdownParser.parseLatexMath("""\frac{\frac{1}{x} + 1}{y - \frac{2}{z}}""")
        assertTrue(nested is MathNode.Fraction)
        val frac = nested as MathNode.Fraction
        assertTrue(frac.hasBar)
        assertTrue(frac.numerator is MathNode.Row && (frac.numerator as MathNode.Row).children[0] is MathNode.Fraction)
        assertTrue(frac.denominator is MathNode.Row && (frac.denominator as MathNode.Row).children.last() is MathNode.Fraction)

        assertTrue(MarkdownParser.parseLatexMath("""\dfrac{a}{b}""") is MathNode.Fraction)
        assertTrue(MarkdownParser.parseLatexMath("""\tfrac{a}{b}""") is MathNode.Fraction)
        assertTrue(MarkdownParser.parseLatexMath("""\cfrac{1}{1+\cfrac{1}{2}}""") is MathNode.Fraction)

        val binom = MarkdownParser.parseLatexMath("""\binom{n}{k}""")
        assertTrue(binom is MathNode.Delimited)
        val inner = (binom as MathNode.Delimited).content
        assertTrue(inner is MathNode.Fraction && !inner.hasBar)

        val cubeRoot = MarkdownParser.parseLatexMath("""\sqrt[3]{x}""")
        assertTrue(cubeRoot is MathNode.Radical)
        val rad = cubeRoot as MathNode.Radical
        assertNotNull(rad.degree)
        assertEquals("³√(x)", MarkdownParser.prettifyLatexMath("""\sqrt[3]{x}"""))
        assertEquals("√(y)", MarkdownParser.prettifyLatexMath("""\sqrt{y}"""))
    }

    @Test
    fun preservesMultiCharSuperscriptsAndSubscriptsInPrettyAndAst() {
        assertEquals("x^(k+1)", MarkdownParser.prettifyLatexMath("""x^{k+1}"""))
        assertEquals("x^(2n+1)", MarkdownParser.prettifyLatexMath("""x^{2n+1}"""))
        assertEquals("e^(-x²)", MarkdownParser.prettifyLatexMath("""e^{-x^2}"""))
        assertEquals("a_(i,j)", MarkdownParser.prettifyLatexMath("""a_{i,j}"""))
        assertEquals("x²", MarkdownParser.prettifyLatexMath("""x^2"""))
        assertEquals("n₁", MarkdownParser.prettifyLatexMath("""n_1"""))
        assertEquals("xⁿ", MarkdownParser.prettifyLatexMath("""x^n"""))

        val sumNode = MarkdownParser.parseLatexMath("""\sum_{n=1}^{\infty}""")
        assertTrue(sumNode is MathNode.Script)
        val sumScript = sumNode as MathNode.Script
        assertTrue(sumScript.base is MathNode.Operator && (sumScript.base as MathNode.Operator).limitsUnderOver)
        assertNotNull(sumScript.subscript)
        assertNotNull(sumScript.superscript)
        assertEquals("∑_(n=1)^(∞)", MarkdownParser.prettifyLatexMath("""\sum_{n=1}^{\infty}"""))

        val limNode = MarkdownParser.parseLatexMath("""\lim_{x \to 0}""")
        assertTrue(limNode is MathNode.Script)
        assertTrue((limNode as MathNode.Script).base is MathNode.Operator)
        assertEquals("lim_(x → 0)", MarkdownParser.prettifyLatexMath("""\lim_{x \to 0}"""))

        val intNode = MarkdownParser.parseLatexMath("""\int_{0}^{1}""")
        assertTrue(intNode is MathNode.Script)
        val intOp = (intNode as MathNode.Script).base as MathNode.Operator
        assertTrue(intOp.isLarge)
        assertFalse(intOp.limitsUnderOver)
        assertEquals("∫₀¹", MarkdownParser.prettifyLatexMath("""\int_{0}^{1}"""))

        val bothOrder = MarkdownParser.parseLatexMath("""x^{a}_{b}""") as MathNode.Script
        assertNotNull(bothOrder.superscript)
        assertNotNull(bothOrder.subscript)
    }

    @Test
    fun parsesCasesMatricesAndAlignedEnvironments() {
        val casesNode = MarkdownParser.parseLatexMath(
            """
            \begin{cases}
            x^2, & x \ge 0 \\
            -x, & x < 0
            \end{cases}
            """.trimIndent()
        )
        assertTrue(casesNode is MathNode.Cases)
        val cases = casesNode as MathNode.Cases
        assertEquals(2, cases.rows.size)
        assertNotNull(cases.rows[0].second)
        assertNotNull(cases.rows[1].second)

        val pmatrix = MarkdownParser.parseLatexMath("""\begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix}""") as MathNode.Matrix
        assertEquals("(", pmatrix.leftDelimiter)
        assertEquals(")", pmatrix.rightDelimiter)
        assertEquals(2, pmatrix.rows.size)
        assertEquals(2, pmatrix.rows[0].size)

        val bmatrix = MarkdownParser.parseLatexMath("""\begin{bmatrix} a & b \\ c & d \end{bmatrix}""") as MathNode.Matrix
        assertEquals("[", bmatrix.leftDelimiter)
        assertEquals("]", bmatrix.rightDelimiter)

        val bigBmatrix = MarkdownParser.parseLatexMath("""\begin{Bmatrix} 1 \\ 2 \end{Bmatrix}""") as MathNode.Matrix
        assertEquals("{", bigBmatrix.leftDelimiter)
        assertEquals("}", bigBmatrix.rightDelimiter)

        val vmatrix = MarkdownParser.parseLatexMath("""\begin{vmatrix} a & b \\ c & d \end{vmatrix}""") as MathNode.Matrix
        assertEquals("|", vmatrix.leftDelimiter)
        assertEquals("|", vmatrix.rightDelimiter)

        val doubleVmatrix = MarkdownParser.parseLatexMath("""\begin{Vmatrix} u \\ v \end{Vmatrix}""") as MathNode.Matrix
        assertEquals("‖", doubleVmatrix.leftDelimiter)
        assertEquals("‖", doubleVmatrix.rightDelimiter)

        val plainMatrix = MarkdownParser.parseLatexMath("""\begin{matrix} 1 & 0 \\ 0 & 1 \end{matrix}""") as MathNode.Matrix
        assertEquals("", plainMatrix.leftDelimiter)
        assertEquals("", plainMatrix.rightDelimiter)

        val aligned = MarkdownParser.parseLatexMath("""\begin{aligned} x &= 1 + 2 \\ y &= 3 \end{aligned}""")
        assertTrue(aligned is MathNode.AlignedLines)
        val alignedLines = aligned as MathNode.AlignedLines
        assertFalse(alignedLines.centered)
        assertEquals(2, alignedLines.rows.size)
        assertEquals(2, alignedLines.rows[0].size)

        val multiline = MarkdownParser.parseLatexMath("""a = 1 \\ b = 2""")
        assertTrue(multiline is MathNode.AlignedLines)
        assertTrue((multiline as MathNode.AlignedLines).centered)
        assertEquals(2, multiline.rows.size)
    }

    @Test
    fun parsesFontsAccentsGreekFunctionsAndSymbolsWithoutPrefixCollisions() {
        val sets = MarkdownParser.prettifyLatexMath("""\mathbb{R} \cup \mathbb{N} \cup \mathbb{Z} \cup \mathbb{Q} \cup \mathbb{C}""")
        assertEquals("ℝ ∪ ℕ ∪ ℤ ∪ ℚ ∪ ℂ", sets)

        val textCmds = MarkdownParser.prettifyLatexMath(
            """\text{if } \textbf{A} \textit{B} \mathrm{d}x \mathbf{v} \mathit{M} \mathcal{O} \operatorname{argmax}"""
        )
        assertTrue(textCmds.contains("if"))
        assertTrue(textCmds.contains("argmax"))

        val accentKinds = listOf(
            """\overline{AB}""" to MathAccentKind.OVERLINE,
            """\bar{x}""" to MathAccentKind.BAR,
            """\vec{v}""" to MathAccentKind.VEC,
            """\overrightarrow{AB}""" to MathAccentKind.ARROW_RIGHT,
            """\hat{y}""" to MathAccentKind.HAT,
            """\widehat{ABC}""" to MathAccentKind.WIDEHAT,
            """\tilde{f}""" to MathAccentKind.TILDE,
            """\dot{x}""" to MathAccentKind.DOT,
            """\ddot{x}""" to MathAccentKind.DDOT,
            """\underline{z}""" to MathAccentKind.UNDERLINE
        )
        for ((latex, expectedKind) in accentKinds) {
            val node = MarkdownParser.parseLatexMath(latex)
            assertTrue("Expected Accent for $latex", node is MathNode.Accent && node.kind == expectedKind)
        }

        val greekAndFuncs = MarkdownParser.prettifyLatexMath(
            """\alpha + \beta + \varepsilon + \varphi + \vartheta + \Gamma + \Omega + \sin(x) + \pmod{m}"""
        )
        assertTrue(greekAndFuncs.contains("α"))
        assertTrue(greekAndFuncs.contains("ε"))
        assertTrue(greekAndFuncs.contains("ϕ"))
        assertTrue(greekAndFuncs.contains("ϑ"))
        assertTrue(greekAndFuncs.contains("Γ"))
        assertTrue(greekAndFuncs.contains("Ω"))
        assertTrue(greekAndFuncs.contains("sin(x)"))
        assertTrue(greekAndFuncs.contains("(mod m)"))

        val symbols = MarkdownParser.prettifyLatexMath(
            """\forall x \in A \setminus \emptyset, \exists y \notin B \implies x \subseteq y \land p \lor \neg q \therefore \triangle ABC \perp \parallel \cong \simeq \propto \approx \ll \gg \mp \pm \times \div \cdot \ast \star \circ \bullet \oplus \otimes \cdots \vdots \ddots \prime \degree \{ \} \% \& \# \_ \|"""
        )
        assertTrue(symbols.contains("∀"))
        assertTrue(symbols.contains("∈"))
        assertTrue(symbols.contains("∖"))
        assertTrue(symbols.contains("∅"))
        assertTrue(symbols.contains("∃"))
        assertTrue(symbols.contains("∉"))
        assertTrue(symbols.contains("⇒"))
        assertTrue(symbols.contains("⊆"))
        assertTrue(symbols.contains("∧"))
        assertTrue(symbols.contains("∨"))
        assertTrue(symbols.contains("¬"))
        assertTrue(symbols.contains("∴"))
        assertTrue(symbols.contains("△"))
        assertTrue(symbols.contains("⊥"))
        assertTrue(symbols.contains("∥"))
        assertTrue(symbols.contains("⊗"))
        assertTrue(symbols.contains("⋯"))
        assertTrue(symbols.contains("°"))
        assertTrue(symbols.contains("{}%&#_‖"))

        // Verify \in does not collide with \infty, \int, or \inf
        val noCollision = MarkdownParser.prettifyLatexMath("""\in \infty \int \inf""")
        assertEquals("∈ ∞ ∫ inf", noCollision)
    }

    @Test
    fun parsesDisplayDelimitersInlineMathAndGuardsCurrencyDollars() {
        val md = """
            \[
            a^2 + b^2 = c^2
            \]

            \begin{equation*}
            E = mc^2
            \end{equation*}

            \begin{align*}
            f(x) &= x + 1 \\
            g(x) &= x - 1
            \end{align*}

            \begin{cases}
            1, & x > 0 \\
            0, & x \le 0
            \end{cases}

            ```math
            \det(A) = 0
            ```

            ```latex
            \nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}
            ```

            Mid-line ${'$'}${'$'}x^{k+1}${'$'}${'$'} plus ${'$'}y_1${'$'} and \(z_2\).
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)
        assertEquals(7, blocks.size)
        assertTrue(blocks[0] is MdBlock.MathBlock)
        assertTrue(blocks[1] is MdBlock.MathBlock)
        assertTrue(blocks[2] is MdBlock.MathBlock)
        assertTrue((blocks[2] as MdBlock.MathBlock).node is MathNode.AlignedLines)
        assertTrue(blocks[3] is MdBlock.MathBlock)
        assertTrue((blocks[3] as MdBlock.MathBlock).node is MathNode.Cases)
        assertTrue(blocks[4] is MdBlock.MathBlock)
        assertTrue(blocks[5] is MdBlock.MathBlock)

        val para = blocks[6] as MdBlock.Paragraph
        val maths = para.inlines.filterIsInstance<MdInline.InlineMath>()
        assertEquals(3, maths.size)
        assertEquals("x^(k+1)", maths[0].pretty)
        assertEquals("y₁", maths[1].pretty)
        assertEquals("z₂", maths[2].pretty)

        // Currency safety tests
        val currencyCases = listOf(
            "The price is $100 today.",
            "Total is $10 + $20 = $30.",
            "Budget between $10 and $20 only.",
            "Range $10-$20 per item.",
            "Spaced $ 100 and $ 200."
        )
        for (text in currencyCases) {
            val parsed = MarkdownParser.parseInlines(text)
            assertTrue("Should not parse currency as math: $text", parsed.none { it is MdInline.InlineMath })
        }

        // Currency followed by genuine math on the same line
        val mixed = MarkdownParser.parseInlines("I paid $10 for ${'$'}x + 1${'$'} units.")
        val mixedMath = mixed.filterIsInstance<MdInline.InlineMath>()
        assertEquals(1, mixedMath.size)
        assertEquals("x + 1", mixedMath[0].raw)
    }

    @Test
    fun parsesTableCellInlinesBoldItalicDoubleUnderscoreTaskListsAndIndentedBullets() {
        val md = """
            | **公式** | `代码` | 说明 |
            | --- | --- | --- |
            | ${'$'}\frac{1}{2}${'$'} | `half` | ***重要*** __加粗__ ${'$'}|x|${'$'} |

            - [ ] 待办事项
            - [x] 已完成
              - [X] 子任务
              - 缩进列表项
                1. 缩进编号
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)
        val table = blocks[0] as MdBlock.Table
        assertTrue(table.headerInlines[0].any { it is MdInline.Bold && it.text == "公式" })
        assertTrue(table.headerInlines[1].any { it is MdInline.InlineCode && it.code == "代码" })

        val row0 = table.rowInlines[0]
        assertTrue(row0[0].any { it is MdInline.InlineMath && it.raw == """\frac{1}{2}""" })
        assertTrue(row0[1].any { it is MdInline.InlineCode && it.code == "half" })
        assertTrue(row0[2].any { it is MdInline.BoldItalic && it.text == "重要" })
        assertTrue(row0[2].any { it is MdInline.Bold && it.text == "加粗" })
        assertTrue(row0[2].any { it is MdInline.InlineMath && it.raw == "|x|" })

        val todo = blocks[1] as MdBlock.BulletItem
        assertEquals(0, todo.indentLevel)
        assertEquals(false, todo.checked)

        val done = blocks[2] as MdBlock.BulletItem
        assertEquals(0, done.indentLevel)
        assertEquals(true, done.checked)

        val subTask = blocks[3] as MdBlock.BulletItem
        assertEquals(1, subTask.indentLevel)
        assertEquals(true, subTask.checked)

        val subBullet = blocks[4] as MdBlock.BulletItem
        assertEquals(1, subBullet.indentLevel)
        assertNull(subBullet.checked)

        val subNum = blocks[5] as MdBlock.NumberedItem
        assertEquals(2, subNum.indentLevel)
        assertEquals("1.", subNum.number)
    }
}
