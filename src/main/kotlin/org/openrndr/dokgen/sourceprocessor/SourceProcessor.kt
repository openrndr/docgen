package org.openrndr.dokgen.sourceprocessor

import kastree.ast.MutableVisitor
import kastree.ast.Node
import kastree.ast.Visitor
import kastree.ast.Writer
import kastree.ast.psi.Converter
import kastree.ast.psi.Parser
import org.openrndr.dokgen.Doc


data class AppModel(
    val imports: MutableList<String> = mutableListOf(),
    var body: String? = null
)


object SourceProcessor {
    data class Result(
        val doc: String,
        val appSources: List<String>,
        val media: List<String>
    )


    fun String.normalizeAnnotation(): String {
        return this.replace("org.openrndr.dokgen.annotations.", "")
    }

    fun process(
        source: String,
        packageDirective: String,
        mkLink: ((Int) -> String)? = null
    ): Result {
        val doc = Doc()
        val applications = mutableListOf<AppModel>()

        val extrasMap = Converter.WithExtras()
        val ast = Parser(extrasMap).parseFile(source)


        val astWithoutExcluded = MutableVisitor.postVisit(ast) { v, parent ->
            when (v) {
                is Node.Expr.Annotated -> {
                    val annotation = v.anns.first().anns.first()
                    val annotationName = annotation.names.joinToString(".").normalizeAnnotation()
                    val clean = v.copy(anns = emptyList())
                    when (annotationName) {
                        "Exclude" -> {
                            val removable =
                                listOf(
                                    Node.Expr.StringTmpl.Elem.Regular("REMOVE ME")
                                )
                            clean.copy(
                                expr = Node.Expr.StringTmpl(elems = removable, raw = true)
                            )
                        }
                        else -> {
                            v
                        }
                    }

                }
                else -> {
                    v
                }
            }

        }

        var appCount = -1
        Visitor.visit(astWithoutExcluded) { v, parent ->
            when (v) {
                is Node.WithModifiers -> {
                    if (v.anns.isNotEmpty()) {
                        val modsWithoutAnnotations = v.mods.filter { it !is Node.Modifier.AnnotationSet }
                        val annotation = v.anns.first().anns.first()
                        val annotationName = annotation.names.joinToString(".").normalizeAnnotation()
                        when (annotationName) {
                            "Code" -> {
                                val field = v.javaClass.getDeclaredField("mods")
                                field.isAccessible = true
                                field.set(v, modsWithoutAnnotations)
                                field.isAccessible = false
                                val codeText = Writer.write(v, extrasMap)
                                doc.elements.add(
                                    Doc.Element.Code(codeText)
                                )
                            }
                        }
                    }
                }
                is Node.Expr.Annotated -> {
                    if (v.anns.isNotEmpty()) {

                        val annotation = v.anns.first().anns.first()
                        val maybeApplication = annotation.names.joinToString(".").normalizeAnnotation()

                        // when this annotation is Application then just increase the appCount and process the next annotation if exists
                        val annotationName = if (maybeApplication == "Application") {
                            appCount++
                            if (v.anns.size > 1) {
                                v.anns[1].anns.first().names.joinToString(".").normalizeAnnotation()
                            } else {
                                null
                            }
                        } else {
                            maybeApplication
                        }

                        when (annotationName) {

                            "Text" -> {
                                val text = stringExpr(v.expr)
                                doc.elements.add(
                                    Doc.Element.Markdown(text)
                                )
                            }
                            "Code" -> {
                                val codeWithoutAnnotations = v.copy(anns = emptyList())
                                val codeText = Writer.write(codeWithoutAnnotations, extrasMap)
                                doc.elements.add(
                                    Doc.Element.Code(codeText)
                                )

                                mkLink?.let {
                                    if (appCount != -1) {
                                        val link = mkLink(appCount)
                                        doc.elements.add(
                                            Doc.Element.Markdown("""
                                                [Link to the full example]($link)
                                            """.trimIndent())
                                        )
                                    }
                                }
                            }
                            "Code.Block" -> {
                                val call = v.expr
                                if (call is Node.Expr.Call && (call.expr as Node.Expr.Name).name == "run") {
                                    val codeText = call.lambda!!.func.block!!.stmts.map { e ->
                                        Writer.write(e, extrasMap)
                                    }.joinToString("\n")
                                    doc.elements.add(
                                        Doc.Element.Code(codeText)
                                    )

                                    mkLink?.let {
                                        if (appCount != -1) {
                                            val link = mkLink(appCount)
                                            doc.elements.add(
                                                Doc.Element.Markdown("""
                                                [Link to the full example]($link)
                                            """.trimIndent())
                                            )
                                        }
                                    }

                                } else {
                                    throw RuntimeException("you can only use the Code.Block annotation on run blocks.")
                                }
                            }
                            "Media.Image" -> {
                                val link = stringExpr(v.expr)
                                doc.elements.add(
                                    Doc.Element.Media.Image(link.trim())
                                )
                            }
                            "Media.Video" -> {
                                val link = stringExpr(v.expr)
                                doc.elements.add(
                                    Doc.Element.Media.Video(link.trim())
                                )
                            }
                        }
                    }
                }
            }
        }


        val cleanAst = MutableVisitor.postVisit(ast) { v, _ ->
            when (v) {
                is Node.Expr.Annotated -> {
                    val annotation = v.anns.first().anns.first()
                    val withoutAnns = v.copy(anns = emptyList())
                    val annotationName = annotation.names.first().normalizeAnnotation()
                    if (annotationName == "Application") {
                        val codeTxt = Writer.write(withoutAnns, extrasMap)
                        applications.add(
                            AppModel(
                                body = codeTxt
                            )
                        )
                    }

                    when (annotationName.normalizeAnnotation()) {
                        "Text", "Media" -> {
                            withoutAnns.copy(
                                expr = (v.expr as Node.Expr.StringTmpl).copy(
                                    elems = listOf(
                                        Node.Expr.StringTmpl.Elem.Regular("REMOVE ME")
                                    )
                                )
                            )
                        }
                        else -> {
                            withoutAnns
                        }
                    }
                }
                else -> {
                    v
                }
            }
        }

        Visitor.visit(cleanAst) { v, parent ->
            when (v) {
                is Node.Import -> {
                    if (!v.names.contains("annotations")) {
                        applications.forEach {
                            it.imports.add(
                                Writer.write(v, extrasMap)
                            )
                        }
                    }
                }
            }
        }


        val appSources = applications.map {
            appTemplate(
                packageDirective,
                it.imports,
                it.body!!
            ).split("\n").filter {
                !it.contains("REMOVE ME")
            }.joinToString("\n")
        }

        val renderedDoc = renderDoc(doc).split("\n").filter {
            !it.contains("REMOVE ME")
        }.joinToString("\n")

        val mediaLinks = doc.elements.filter { it is Doc.Element.Media }.map { it as Doc.Element.Media }
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


        return Result(
            doc = renderedDoc,
            appSources = appSources,
            media = mediaLinks
        )
    }
}