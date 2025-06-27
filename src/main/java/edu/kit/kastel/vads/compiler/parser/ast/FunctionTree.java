package edu.kit.kastel.vads.compiler.parser.ast;

import edu.kit.kastel.vads.compiler.Span;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;
import java.util.List;

public record FunctionTree(TypeTree returnType, NameTree name, List<DeclarationTree> parameters, BlockTree body) implements Tree {
    public FunctionTree {
        parameters = List.copyOf(parameters);
    }

    @Override
    public Span span() {
        return new Span.SimpleSpan(returnType().span().start(), body().span().end());
    }

    @Override
    public <T, R> R accept(Visitor<T, R> visitor, T data) {
        return visitor.visit(this, data);
    }
}
