package edu.kit.kastel.vads.compiler.lexer;

import edu.kit.kastel.vads.compiler.Span;

public record Operator(OperatorType type, Span span) implements Token {

    @Override
    public boolean isOperator(OperatorType operatorType) {
        return type() == operatorType;
    }

    @Override
    public String asString() {
        return type().toString();
    }

    public enum OperatorType {
        // Assignment operators
        ASSIGN("="),
        ASSIGN_PLUS("+="),
        ASSIGN_MINUS("-="),
        ASSIGN_MUL("*="),
        ASSIGN_DIV("/="),
        ASSIGN_MOD("%="),
        ASSIGN_BIT_AND("&="),
        ASSIGN_BIT_XOR("^="),
        ASSIGN_BIT_OR("|="),
        ASSIGN_SHIFT_LEFT("<<="),
        ASSIGN_SHIFT_RIGHT(">>="),

        // Arithmetic operators
        PLUS("+"),
        MINUS("-"),
        MUL("*"),
        DIV("/"),
        MOD("%"),

        // Bitwise operators
        BIT_AND("&"),
        BIT_XOR("^"),
        BIT_OR("|"),
        BIT_NOT("~"),

        // Shift operators
        SHIFT_LEFT("<<"),
        SHIFT_RIGHT(">>"),

        // Comparison operators
        LESS("<"),
        LESS_EQUAL("<="),
        GREATER(">"),
        GREATER_EQUAL(">="),
        EQUAL("=="),
        NOT_EQUAL("!="),

        // Logical operators
        LOGICAL_AND("&&"),
        LOGICAL_OR("||"),
        LOGICAL_NOT("!"),

        // Ternary operator
        QUESTION("?"),
        COLON(":");

        private final String value;

        OperatorType(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return this.value;
        }
    }
}
