package org.openrndr.dokgen.sourceprocessor

import kastree.ast.MutableVisitor
import kastree.ast.Node
import kastree.ast.Writer
import kastree.ast.psi.Converter
import kastree.ast.psi.Parser
import java.lang.IllegalStateException


// the state of folding the syntax tree
private data class State(
    val doc: Doc = Doc(),
    val applications: List<String> = listOf(),
    val imports: List<String> = listOf(),
    val inApplication: InApplication? = null
) {
    data class InApplication(val node: Node)


    fun addApplication(text: String): State {
        return copy(applications = applications + text)
    }

    fun addImport(import: String): State {
        return copy(imports = imports + import)
    }

    fun updateDoc(newDoc: Doc): State {
        return copy(doc = newDoc)
    }
}

// some nodes need to be removed from the document
// but i couldn't find a way to do it
// as a workaround i replace them with a string node representing garbage to remove at the end
val garbage =
    Node.Expr.StringTmpl(elems = listOf(
        Node.Expr.StringTmpl.Elem.Regular("GARBAGE")
    ), raw = true)


private fun String.removeGarbage(): String {
    return this.split("\n").filter { !it.contains("GARBAGE") }.joinToString("\n")
}


data class Doc(
    val elements: List<Element> = listOf()
) {
    sealed class Element {
        class Code(val value: String) : Element()
        class Markdown(val text: String) : Element()
        sealed class Media : Element() {
            class Image(val src: String) : Media()
            class Video(val src: String) : Media()
        }
    }

    fun add(element: Element): Doc {
        return copy(elements = elements + element)
    }
}


fun <A, B, C> Pair<A?, B?>.map2(fn: (A, B) -> C): C? {
    val maybeA = this.first
    val maybeB = this.second
    return maybeA?.let { a ->
        maybeB?.let { b ->
            fn(a, b)
        }
    }
}

// some helpers
private fun Node.Modifier.AnnotationSet.Annotation.getName(): String {
    return this.names.joinToString(".").normalizeAnnotationName()
}

private fun String.normalizeAnnotationName(): String {
    return this.replace("org.openrndr.dokgen.annotations.", "")
}


private fun stringExpr(expr: Node.Expr): String {
    when (expr) {
        is Node.Expr.StringTmpl -> {
            return expr.elems.map {
                when (it) {
                    is Node.Expr.StringTmpl.Elem.Regular -> it.str
                    is Node.Expr.StringTmpl.Elem.ShortTmpl -> "$${it.str}"
                    else -> throw RuntimeException("unexpected string type: $it")
                }
            }.joinToString("")
        }
        else -> {
            throw RuntimeException("cannot convert expression $expr to string")
        }
    }
}


private fun Node.filterAnnotated(fn: (Node.Expr.Annotated) -> Boolean): Node {
    return MutableVisitor.postVisit(this) { node, _ ->
        when (node) {
            is Node.Expr.Annotated -> {
                if (fn(node)) {
                    node
                } else {
                    garbage
                }
            }
            else -> node
        }
    }
}

private fun Node.mapAnnotated(fn: (Node.Expr.Annotated) -> Node): Node {
    return MutableVisitor.postVisit(this) { node, _ ->
        when (node) {
            is Node.Expr.Annotated -> {
                fn(node)
            }
            else -> node
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun <N : Node> filterExcluded(n: N): N {
    return n.filterAnnotated { child ->
        val annotated = child.anns.firstOrNull()?.anns?.firstOrNull()?.names?.firstOrNull()
        when (annotated) {
            "Exclude" -> false
            else -> true
        }
    } as N
}

private class ProcessAnnotatedNode(
    val printNode: (Node) -> String,
    val maybeMkLink: ((Int) -> String)?
) {

    private fun Node.Expr.Annotated.withoutAnnotations(): Node.Expr.Annotated {
        return copy(anns = listOf())
    }

    operator fun invoke(node: Node.Expr.Annotated, state: State): State {
        val doc = state.doc
        val annotation = node.anns.firstOrNull()?.anns?.firstOrNull() ?: return state
        val annotationName = annotation.getName()
        return when (annotationName) {

            "Application" -> {

                val appSource = node.withoutAnnotations().run {
                    filterAnnotated { node ->
                        val annotated = node.anns.firstOrNull()?.anns?.firstOrNull()?.names?.firstOrNull()
                        when (annotated) {
                            "Text", "Media" -> false
                            else -> true
                        }
                    }.mapAnnotated { node ->
                        node.withoutAnnotations()
                    }
                }.run(printNode)

                val newState = state.copy(
                    inApplication = State.InApplication(node)
                ).addApplication(
                    appSource
                )

                val nextAnnotations = if (node.anns.size > 1) {
                    node.anns.subList(1, node.anns.size)
                } else {
                    null
                }

                nextAnnotations?.let {
                    this(
                        node.copy(anns = nextAnnotations),
                        newState
                    )
                } ?: newState
            }

            "Text" -> {
                val text = stringExpr(node.expr)
                val newDoc = doc.add(Doc.Element.Markdown(text))
                state.updateDoc(newDoc)
            }

            "Code", "Code.Block" -> {
                val mkDoc = { text: String ->
                    doc.add(Doc.Element.Code(text)).let { doc ->
                        Pair(maybeMkLink, state.inApplication).map2 { mkLink, _ ->
                            val appCount = state.applications.size
                            val link = mkLink(appCount)
                            doc.add(
                                Doc.Element.Markdown("""
                                                [Link to the full example]($link)
                                            """.trimIndent())
                            )
                        } ?: doc
                    }
                }

                val text = when (annotationName) {
                    "Code" -> {
                        val cleaned = node.run {
                            filterExcluded(this)
                        }.mapAnnotated {
                            it.withoutAnnotations()
                        }
                        printNode(cleaned)
                    }
                    "Code.Block" -> {
                        val call = node.run {
                            filterExcluded(this)
                        }.mapAnnotated {
                            it.withoutAnnotations()
                        }.run {
                            this as Node.Expr.Annotated
                        }.expr

                        if (call is Node.Expr.Call && (call.expr as Node.Expr.Name).name == "run") {
                            call.lambda!!.func.block!!.stmts.map { e ->
                                printNode(e)
                            }.joinToString("\n")
                        } else {
                            throw RuntimeException("you can only use the Code.Block annotation on run blocks.")
                        }
                    }
                    else -> {
                        throw  IllegalStateException()
                    }
                }

                val newDoc = mkDoc(text)
                state.updateDoc(newDoc)
            }


            "Media.Image" -> {
                val link = stringExpr(node.expr)
                val newDoc = doc.add(
                    Doc.Element.Media.Image(link.trim())
                )
                state.updateDoc(newDoc)
            }
            "Media.Video" -> {
                val link = stringExpr(node.expr)
                val newDoc = doc.add(
                    Doc.Element.Media.Video(link.trim())
                )
                state.updateDoc(newDoc)
            }


            else -> state
        }
    }
}


private class AstFolder(
    val printNode: (Node) -> String,
    maybeMkLink: ((Int) -> String)?
) : Folder<State> {
    val processAnnotatedNode = ProcessAnnotatedNode(
        printNode,
        maybeMkLink
    )
    override val pre: (State, Node) -> State =
        { state, node ->
            when (node) {
                is Node.Expr.Annotated -> {
                    processAnnotatedNode(node, state)
                }
                // some annotated nodes are not showing up as Node.Expr.Annotated but
                // as Node.WithModifiers where the annotations are in node.mods
                is Node.WithModifiers -> {
                    if (node.anns.isNotEmpty()) {
                        val annotation = node.anns.first().anns.first()
                        val annotationName = annotation.getName()
                        when (annotationName) {
                            "Code" -> {
                                // here node is not a data class so cannot just copy if with the annotations left out
                                // couldn't find a better way of obtaining the same node without annotations,
                                // so mutating it with reflection before writing it to a string
                                val field = node.javaClass.getDeclaredField("mods")
                                field.isAccessible = true
                                val modsWithoutAnnotations = node.mods.filter { it !is Node.Modifier.AnnotationSet }
                                field.set(node, modsWithoutAnnotations)
                                field.isAccessible = false
                                val codeText = printNode(node)
                                val newDoc = state.doc.add(Doc.Element.Code(codeText))
                                state.updateDoc(newDoc)
                            }
                            else -> state
                        }
                    } else {
                        state
                    }
                }
                is Node.Import -> {
                    if (node.names.contains("dokgen")) {
                        state
                    } else {
                        state.addImport(printNode(node))
                    }
                }
                else -> {
                    state
                }
            }
        }
    override val post: ((State, Node) -> State) = { state, node ->
        // if we're inside a node annotated with @Application
        // and the the node is the same as what we've saved in state, then we've exited the node
        if (state.inApplication != null && node == state.inApplication.node) {
            state.copy(inApplication = null)
        } else {
            state
        }
    }
}


object SourceProcessor {

    // what will be produced
    data class Output(
        val doc: String,
        val appSources: List<String>,
        val media: List<String>
    )


    fun process(
        source: String,
        packageDirective: String,
        mkLink: ((Int) -> String)? = null
    ): Output {

        val initialState = State()

        val extrasMap = Converter.WithExtras()
        val ast = Parser(extrasMap).parseFile(source)
        val printNode = { node: Node ->
            Writer.write(node, extrasMap)
        }

        val folder = AstFolder(printNode, mkLink)

        val result = ast.run {
            fold(initialState, folder)
        }

        val renderedDoc = renderDoc(result.doc).removeGarbage()
        val appSources = result.applications.map {
            appTemplate(
                packageDirective,
                result.imports,
                it
            ).removeGarbage()
        }

        val mediaLinks = result.doc.elements.filter { it is Doc.Element.Media }.map { it as Doc.Element.Media }
            .map {
                when (it) {
                    is Doc.Element.Media.Image -> {
                        it.src
                    }

                    is Doc.Element.Media.Video -> {
                        it.src
                    }
                }
            }

        return Output(
            doc = renderedDoc,
            appSources = appSources,
            media = mediaLinks
        )
    }


}