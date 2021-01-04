import $ivy.`com.lihaoyi::ammonite-ops:2.2.0`, ammonite.ops._

import $file.common, common._, Suite._
import $file.spoofax, spoofax._
import $ivy.`org.metaborg:org.spoofax.jsglr2.benchmark:2.6.0-SNAPSHOT`

import org.spoofax.interpreter.terms.IStrategoTerm
import org.spoofax.jsglr2.{JSGLR2Variant, JSGLR2Success, JSGLR2Failure}
import org.spoofax.jsglr2.integration.IntegrationVariant
import org.spoofax.jsglr2.benchmark.jsglr2.util.JSGLR2MultiParser
import org.metaborg.parsetable.ParseTableVariant
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

trait Parser {
    def id: String
    def parse(input: String): ParseResult
    def recovery: Boolean
}

case class JSGLR2Parser(language: Language, jsglr2Preset: JSGLR2Variant.Preset, incremental: Boolean = false) extends Parser {
    val id = "jsglr2-" + jsglr2Preset.name
    val variant = new IntegrationVariant(
        new ParseTableVariant(),
        jsglr2Preset.variant
    )
    val recovery = variant.parser.recovery
    val jsglr2 = getJSGLR2(variant, language)
    def parse(input: String) = jsglr2.parseResult(input) match {
        case success: JSGLR2Success[IStrategoTerm] =>
            if (success.isAmbiguous)
                ParseFailure(Some("ambiguous"), Ambiguous)
            else
                ParseSuccess(Some(success.ast))
        case failure: JSGLR2Failure[_] => ParseFailure(Some(failure.parseFailure.failureCause.toMessage.toString), Invalid)
    }
    val jsglr2Multi = new JSGLR2MultiParser(jsglr2)
    def parseMulti(inputs: String*) = jsglr2Multi.parse(inputs:_*).asScala
}

case class JSGLR1Parser(language: Language) extends Parser {
    val id = "jsglr1"
    val jsglr1 = getJSGLR1(language)
    def parse(input: String) = Try(jsglr1.parse(input, null, null)) match {
        case Success(_) => ParseSuccess(None)
        case Failure(_) => ParseFailure(None, Invalid)
    }
    val recovery = true
}

import $ivy.`org.antlr:antlr4-runtime:4.7.2`

import org.antlr.v4.runtime.{Lexer => ANTLR_Lexer, Parser => ANTLR_Parser, _}
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.spoofax.jsglr2.benchmark.antlr4.{Java8Lexer => ANTLR_Java8Lexer, Java8Parser => ANTLR_Java8Parser}
import org.spoofax.jsglr2.benchmark.antlr4.{JavaLexer => ANTLR_JavaLexer, JavaParser => ANTLR_JavaParser}

case class ANTLRParser[ANTLR__Lexer <: ANTLR_Lexer, ANTLR__Parser <: ANTLR_Parser](id: String, getLexer: CharStream => ANTLR__Lexer, getParser: TokenStream => ANTLR__Parser, doParse: ANTLR__Parser => Tree) extends Parser {
    def parse(input: String) = {
        try {
            val charStream = CharStreams.fromString(input)
            val lexer = getLexer(charStream)

            val tokens = new CommonTokenStream(lexer)
            val parser = getParser(tokens)

            parser.setErrorHandler(new BailErrorStrategy())
            
            doParse(parser)

            ParseSuccess(None)
        } catch {
            case e: ParseCancellationException => ParseFailure(None, Invalid)
        }
    }
    def recovery = false
}

trait ParseResult {
    def isValid: Boolean
    def isInvalid = !isValid
}
case class ParseSuccess(ast: Option[IStrategoTerm]) extends ParseResult {
    def isValid = true
}
case class ParseFailure(error: Option[String], reason: ParseFailureReason) extends ParseResult {
    def isValid = false
}

trait ParseFailureReason
object Invalid extends ParseFailureReason
object Ambiguous extends ParseFailureReason
object Timeout extends ParseFailureReason

object Parser {
    def variants(language: Language)(implicit suite: Suite): Seq[Parser] = Seq(
        //JSGLR1Parser(language),
        JSGLR2Parser(language, JSGLR2Variant.Preset.standard),
        JSGLR2Parser(language, JSGLR2Variant.Preset.elkhound),
        JSGLR2Parser(language, JSGLR2Variant.Preset.incremental, true),
        JSGLR2Parser(language, JSGLR2Variant.Preset.recovery),
        JSGLR2Parser(language, JSGLR2Variant.Preset.recoveryElkhound),
        //JSGLR2Parser(language, JSGLR2Variant.Preset.recoveryIncremental, true),
    ) ++ language.antlrBenchmarks.map { benchmark =>
        benchmark.id match {
            case "antlr" =>
                ANTLRParser[ANTLR_Java8Lexer, ANTLR_Java8Parser](benchmark.id, new ANTLR_Java8Lexer(_), new ANTLR_Java8Parser(_), _.compilationUnit)
            case "antlr-optimized" =>
                ANTLRParser[ANTLR_JavaLexer, ANTLR_JavaParser](benchmark.id, new ANTLR_JavaLexer(_), new ANTLR_JavaParser(_), _.compilationUnit)
        }
    }
}
