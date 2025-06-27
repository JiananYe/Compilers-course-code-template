package edu.kit.kastel.vads.compiler.parser.visitor;

import edu.kit.kastel.vads.compiler.parser.ast.*;

public interface Visitor<T, R> {
    R visit(ProgramTree tree, T data);
    R visit(FunctionTree tree, T data);
    R visit(BlockTree tree, T data);
    R visit(DeclarationTree tree, T data);
    R visit(AssignmentTree tree, T data);
    R visit(ReturnTree tree, T data);
    R visit(IfTree tree, T data);
    R visit(WhileTree tree, T data);
    R visit(ForTree tree, T data);
    R visit(BreakTree tree, T data);
    R visit(ContinueTree tree, T data);
    R visit(BinaryOperationTree tree, T data);
    R visit(NegateTree tree, T data);
    R visit(UnaryOperationTree tree, T data);
    R visit(IdentExpressionTree tree, T data);
    R visit(LiteralTree tree, T data);
    R visit(BooleanLiteralTree tree, T data);
    R visit(TernaryTree tree, T data);
    R visit(LValueIdentTree tree, T data);
    R visit(NameTree tree, T data);
    R visit(TypeTree tree, T data);
    R visit(CallExpressionTree tree, T data);
}
