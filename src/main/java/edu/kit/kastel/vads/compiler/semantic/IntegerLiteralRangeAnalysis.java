package edu.kit.kastel.vads.compiler.semantic;

import edu.kit.kastel.vads.compiler.parser.ast.LiteralTree;
import edu.kit.kastel.vads.compiler.parser.visitor.NoOpVisitor;
import edu.kit.kastel.vads.compiler.parser.visitor.Unit;
import edu.kit.kastel.vads.compiler.parser.ast.UnaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.IfTree;
import edu.kit.kastel.vads.compiler.parser.ast.WhileTree;
import edu.kit.kastel.vads.compiler.parser.ast.ForTree;
import edu.kit.kastel.vads.compiler.parser.ast.BreakTree;
import edu.kit.kastel.vads.compiler.parser.ast.ContinueTree;
import edu.kit.kastel.vads.compiler.parser.ast.TernaryTree;
import edu.kit.kastel.vads.compiler.parser.ast.BooleanLiteralTree;
import edu.kit.kastel.vads.compiler.parser.ast.NegateTree;
import edu.kit.kastel.vads.compiler.parser.ast.CallExpressionTree;

public class IntegerLiteralRangeAnalysis implements NoOpVisitor<Namespace<Void>> {

    @Override
    public Unit visit(LiteralTree literalTree, Namespace<Void> data) {
      literalTree.parseValue()
          .orElseThrow(
              () -> new SemanticException("invalid integer literal " + literalTree.value())
          );
        return NoOpVisitor.super.visit(literalTree, data);
    }

    @Override
    public Unit visit(UnaryOperationTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(IfTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(WhileTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ForTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BreakTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ContinueTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(TernaryTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BooleanLiteralTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(NegateTree tree, Namespace<Void> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(CallExpressionTree tree, Namespace<Void> data) {
        tree.callee().accept(this, data);
        for (var arg : tree.arguments()) {
            arg.accept(this, data);
        }
        return Unit.INSTANCE;
    }
}
