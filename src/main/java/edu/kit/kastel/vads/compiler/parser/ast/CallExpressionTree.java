package edu.kit.kastel.vads.compiler.parser.ast;

import edu.kit.kastel.vads.compiler.Span;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;
import java.util.List;

public record CallExpressionTree(NameTree callee, List<ExpressionTree> arguments, Span span) implements ExpressionTree {
    public CallExpressionTree {
        arguments = List.copyOf(arguments);
    }
    @Override
    public <T, R> R accept(Visitor<T, R> visitor, T data) {
        return visitor.visit(this, data);
    }
} 