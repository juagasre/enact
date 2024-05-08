package eu.assistiot.semantic_repo.core.documentation.local

import cats.effect.{IO, Resource}
import laika.api.Transformer
import laika.ast.MessageFilter
import laika.bundle.{ExtensionBundle, ParserBundle}
import laika.directive.{Blocks, Spans}
import laika.directive.std.*
import laika.factory.{MarkupFormat, RenderFormat}
import laika.helium.Helium
import laika.io.api.TreeTransformer
import laika.io.implicits.*
import laika.parse.code.SyntaxHighlighting
import laika.parse.directive.{BlockDirectiveParsers, SpanDirectiveParsers}
import laika.theme.ThemeProvider

/**
 * Utilities that dig into Laika to get precisely the behavior that we need.
 */
object LaikaUtil:
  /**
   * Creates a [[TreeTransformer]] for a given input-output format pair, with optional extra extension bundles.
   * @param from source markup format
   * @param to format to render in
   * @param extraBundles (optional) extra, format-specific extension bundles
   * @param theme (optional) the theme, if there's a need to customize it
   * @return
   */
  def makeTransformer[FMT](from: MarkupFormat, to: RenderFormat[FMT], extraBundles: Seq[ExtensionBundle] = Seq(),
                           theme: ThemeProvider = Helium.defaults.build):
  Resource[IO, TreeTransformer[IO]] = Transformer
    .from(from)
    .to(to)
    // Disable config headers and custom Laika markup extensions
    .strict
    // Enable selected safe directives
    .using(SafeMarkupDirectives)
    // Enable markup-specific extensions
    .using(Seq(SyntaxHighlighting) ++ extraBundles *)
    // Only fail on fatal errors
    .failOnMessages(MessageFilter.Fatal)
    .renderMessages(MessageFilter.Error)
    .parallel[IO]
    .withTheme(theme)
    .build

  /**
   * Extension bundle that disables parsing config files and config headers in markup files.
   */
  object SafeMarkupDirectives extends ExtensionBundle:
    override def description = "Safe markup directives for strict mode"

    /**
     * A subset of standard Laika directives, with some unsafe stuff removed.
     */
    private val blockDirectives = List(
      BreadcrumbDirectives.forBlocks,
      NavigationTreeDirectives.forBlocks,
      ImageDirectives.forBlocks,
      StandardDirectives.callout,
      StandardDirectives.todoBlock,
    )

    private val spanDirectives = List(
      ImageDirectives.forSpans,
      StandardDirectives.todoSpan,
    )

    override lazy val parsers: ParserBundle = ParserBundle(
      blockParsers = Seq(BlockDirectiveParsers.blockDirective(Blocks.toMap(blockDirectives))),
      spanParsers = Seq(
        SpanDirectiveParsers.spanDirective(Spans.toMap(spanDirectives)),
        SpanDirectiveParsers.contextRef
      )
    )
