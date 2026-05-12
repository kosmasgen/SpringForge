package com.sqldomaingen.parser;

import com.sqldomaingen.util.Constants;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.springframework.stereotype.Component;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

/**
 * SQL parser wrapper around the ANTLR PostgreSQL grammar.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Validate that SQL input exists</li>
 *   <li>Create and configure the ANTLR lexer/parser</li>
 *   <li>Expose helper methods to parse full scripts, constraints, or just tokens</li>
 * </ul>
 */
@Getter
@Setter
@Component
@AllArgsConstructor
@NoArgsConstructor
@Log4j2
public class SQLParser {

    private String sqlContent;



    /**
     * Creates a configured {@link PostgreSQLParser} for the current {@code sqlContent}.
     * <p>
     * Configuration includes:
     * <ul>
     *   <li>Custom {@link BaseErrorListener} that throws {@link IllegalArgumentException} on syntax errors</li>
     *   <li>ANTLR trace enabled (useful while developing/debugging grammar issues)</li>
     * </ul>
     *
     * @return a ready-to-use ANTLR PostgreSQL parser
     * @throws IllegalArgumentException if {@code sqlContent} is null or blank
     */
    public PostgreSQLParser createParser() {
        if (isSQLContentInvalid()) {
            log.error(Constants.EMPTY_SQL_ERROR_MESSAGE);
            throw new IllegalArgumentException(Constants.EMPTY_SQL_ERROR_MESSAGE);
        }

        log.debug("ANTLR trace is enabled (grammar rule tracing).");

        PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(sqlContent));
        CommonTokenStream tokens = createTokenStream(lexer);
        PostgreSQLParser parser = createParser(tokens);

        // Replace default listeners to fail fast on syntax errors.
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg,
                                    RecognitionException e) {
                throw new IllegalArgumentException(
                        "Syntax error at line " + line + ", position " + charPositionInLine + ": " + msg
                );
            }
        });

        // Enable tracing for debugging (consider disabling for production use).
        parser.setTrace(false);

        return parser;
    }

    /**
     * Creates a token stream from the PostgreSQL lexer.
     *
     * @param lexer PostgreSQL lexer
     * @return token stream
     */
    private CommonTokenStream createTokenStream(PostgreSQLLexer lexer) {
        return new CommonTokenStream(lexer);
    }

    /**
     * Creates a PostgreSQL parser from the token stream.
     *
     * @param tokens parser token stream
     * @return PostgreSQL parser
     */
    private PostgreSQLParser createParser(CommonTokenStream tokens) {
        return new PostgreSQLParser(tokens);
    }

    /**
     * Parses the current SQL script and returns the produced {@link ParseTree}.
     *
     * @return the parse tree created by the ANTLR parser
     * @throws IllegalArgumentException if {@code sqlContent} is invalid or contains syntax errors
     */
    public ParseTree parseTreeFromSQL() {
        if (isSQLContentInvalid()) {
            log.error("Cannot generate ParseTree: SQL content is invalid.");
            throw new IllegalArgumentException(Constants.EMPTY_SQL_ERROR_MESSAGE);
        }

        PostgreSQLParser parser = createParser();

        // Debug: log extracted tokens before running the grammar entry rule.
        CommonTokenStream tokenStream = (CommonTokenStream) parser.getInputStream();
        List<Token> tokens = tokenStream.getTokens();
        log.debug("Tokens extracted: {}", tokens.stream()
                .map(token -> String.format("[%s -> %s]", token.getText(), parser.getVocabulary().getSymbolicName(token.getType())))
                .toList());

        ParseTree tree = parser.sqlScript();

        log.info("ParseTree generated: {}", tree.toStringTree(parser));
        return tree;
    }


    /**
     * Tokenizes the current SQL input and returns the {@link TokenStream}.
     * Useful for debugging lexer behavior without executing grammar rules.
     *
     * @return a token stream produced by the lexer
     * @throws IllegalArgumentException if {@code sqlContent} is invalid
     */
    public TokenStream parseSQL() {
        if (isSQLContentInvalid()) {
            log.error("Cannot generate TokenStream: SQL content is invalid.");
            throw new IllegalArgumentException(Constants.EMPTY_SQL_ERROR_MESSAGE);
        }

        PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(sqlContent));
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // Debug: dump all tokens with their symbolic types.
        tokens.fill();
        for (Token token : tokens.getTokens()) {
            log.debug("Token: '{}' -> Type: {}", token.getText(), lexer.getVocabulary().getSymbolicName(token.getType()));
        }

        log.info("TokenStream generated successfully for SQL content.");
        return tokens;
    }

    /**
     * Returns true when SQL content is missing or empty.
     *
     * @return true when SQL content is invalid
     */
    public boolean isSQLContentInvalid() {
        return sqlContent == null || sqlContent.isEmpty();
    }
}
