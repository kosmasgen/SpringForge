package com.sqldomaingen.parser;

import com.sqldomaingen.model.IndexDefinition;
import lombok.extern.log4j.Log4j2;
import org.antlr.v4.runtime.misc.Interval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
public class CreateIndexDefinition {

    /**
     * Parses a CREATE INDEX statement into an IndexDefinition model.
     *
     * @param ctx ANTLR context for CREATE INDEX
     * @return populated IndexDefinition
     */
    public IndexDefinition processCreateIndex(PostgreSQLParser.CreateIndexStatementContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("CreateIndexStatementContext is null");
        }

        IndexDefinition indexDefinition = new IndexDefinition();
        String rawText = ctx.getText();

        indexDefinition.setName(ctx.IDENTIFIER(0).getText().replace("\"", "").trim());
        indexDefinition.setTableName(ctx.tableName().getText().replace("\"", "").trim());
        indexDefinition.getColumns().addAll(extractColumnExpressions(ctx));
        indexDefinition.setUnique(isUniqueIndex(rawText));
        indexDefinition.setUsingMethod(extractUsingMethod(rawText));
        indexDefinition.setWhereClause(extractWhereClause(ctx));

        log.debug("Parsed index '{}' on table '{}' -> columns={}, unique={}, using={}, where={}",
                indexDefinition.getName(),
                indexDefinition.getTableName(),
                indexDefinition.getColumns(),
                indexDefinition.isUnique(),
                indexDefinition.getUsingMethod(),
                indexDefinition.getWhereClause()
        );

        return indexDefinition;
    }

    /**
     * Extracts index column expressions from the CREATE INDEX statement while preserving
     * token spacing for ASC/DESC and operator classes.
     *
     * @param ctx ANTLR context for CREATE INDEX
     * @return parsed column expressions
     */
    private List<String> extractColumnExpressions(PostgreSQLParser.CreateIndexStatementContext ctx) {
        List<String> columnExpressions = new ArrayList<>();

        for (PostgreSQLParser.IndexElementContext indexElementContext : ctx.indexElement()) {
            String expression = extractOriginalText(indexElementContext)
                    .replace("\"", "")
                    .trim();

            columnExpressions.add(expression);
        }

        return columnExpressions;
    }

    /**
     * Extracts original SQL text for a parser context without losing whitespace between tokens.
     *
     * @param context parser context
     * @return original SQL text for the context
     */
    private String extractOriginalText(org.antlr.v4.runtime.ParserRuleContext context) {
        if (context == null || context.getStart() == null || context.getStop() == null) {
            return "";
        }

        return context.getStart().getInputStream().getText(
                new org.antlr.v4.runtime.misc.Interval(
                        context.getStart().getStartIndex(),
                        context.getStop().getStopIndex()
                )
        );
    }

    /**
     * Determines whether the CREATE INDEX statement is unique.
     *
     * @param rawText raw ANTLR flattened statement text
     * @return true when the index is unique
     */
    private boolean isUniqueIndex(String rawText) {
        String normalized = rawText
                .replaceAll("\\s+", "")
                .toUpperCase(java.util.Locale.ROOT);

        return normalized.contains("CREATEUNIQUEINDEX");
    }

    /**
     * Extracts the USING method from the flattened ANTLR text.
     *
     * @param rawText raw ANTLR flattened statement text
     * @return index method or null
     */
    private String extractUsingMethod(String rawText) {
        Matcher usingMatcher = Pattern
                .compile("(?i)USING(\\w+)")
                .matcher(rawText);

        if (!usingMatcher.find()) {
            return null;
        }

        return usingMatcher.group(1).toLowerCase();
    }

    /**
     * Extracts the partial index WHERE clause while preserving original SQL whitespace.
     *
     * @param ctx ANTLR context for CREATE INDEX
     * @return WHERE clause without the WHERE keyword, or null
     */
    private String extractWhereClause(PostgreSQLParser.CreateIndexStatementContext ctx) {
        if (ctx.getStart() == null || ctx.getStop() == null) {
            return null;
        }

        String originalSql = ctx.getStart().getInputStream().getText(
                new Interval(
                        ctx.getStart().getStartIndex(),
                        ctx.getStop().getStopIndex()
                )
        );

        Matcher whereMatcher = Pattern
                .compile("(?is)\\bWHERE\\b(.+)$")
                .matcher(originalSql);

        if (!whereMatcher.find()) {
            return null;
        }

        return removeTrailingSemicolon(whereMatcher.group(1).trim());
    }

    /**
     * Removes a trailing SQL semicolon from a parsed fragment.
     *
     * @param value parsed SQL fragment
     * @return SQL fragment without trailing semicolon
     */
    private String removeTrailingSemicolon(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String trimmedValue = value.trim();

        if (trimmedValue.endsWith(";")) {
            return trimmedValue.substring(0, trimmedValue.length() - 1).trim();
        }

        return trimmedValue;
    }
}