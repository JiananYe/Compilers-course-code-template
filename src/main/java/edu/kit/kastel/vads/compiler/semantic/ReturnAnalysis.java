package edu.kit.kastel.vads.compiler.semantic;

import edu.kit.kastel.vads.compiler.parser.ast.FunctionTree;
import edu.kit.kastel.vads.compiler.parser.ast.ReturnTree;
import edu.kit.kastel.vads.compiler.parser.visitor.NoOpVisitor;
import edu.kit.kastel.vads.compiler.parser.visitor.Unit;
import edu.kit.kastel.vads.compiler.parser.ast.UnaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.IfTree;
import edu.kit.kastel.vads.compiler.parser.ast.WhileTree;
import edu.kit.kastel.vads.compiler.parser.ast.ForTree;
import edu.kit.kastel.vads.compiler.parser.ast.BreakTree;
import edu.kit.kastel.vads.compiler.parser.ast.ContinueTree;
import edu.kit.kastel.vads.compiler.parser.ast.TernaryTree;
import edu.kit.kastel.vads.compiler.parser.ast.BlockTree;
import edu.kit.kastel.vads.compiler.parser.ast.BooleanLiteralTree;
import edu.kit.kastel.vads.compiler.parser.ast.NegateTree;
import edu.kit.kastel.vads.compiler.parser.ast.ExpressionTree;

/// Checks that functions return.
/// Currently only works for straight-line code.
class ReturnAnalysis implements NoOpVisitor<ReturnAnalysis.ReturnState> {

    static class ReturnState {
        boolean returns = false;
    }

    @Override
    public Unit visit(ReturnTree returnTree, ReturnState data) {
        data.returns = true;
        return NoOpVisitor.super.visit(returnTree, data);
    }

    @Override
    public Unit visit(FunctionTree functionTree, ReturnState data) {
        if (!data.returns) {
            throw new SemanticException("function " + functionTree.name() + " does not return");
        }
        data.returns = false;
        return NoOpVisitor.super.visit(functionTree, data);
    }

    @Override
    public Unit visit(UnaryOperationTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(IfTree tree, ReturnState data) {
        Boolean constCond = tryConstantFold(tree.condition());
        boolean thenReturns = false;
        boolean elseReturns = false;
        if (constCond != null) {
            if (constCond) {
                ReturnState thenState = new ReturnState();
                tree.thenBranch().accept(this, thenState);
                thenReturns = thenState.returns;
                elseReturns = true; // else branch is unreachable
            } else {
                if (tree.elseBranch() != null) {
                    ReturnState elseState = new ReturnState();
                    tree.elseBranch().accept(this, elseState);
                    elseReturns = elseState.returns;
                } else {
                    elseReturns = false;
                }
                thenReturns = true; // then branch is unreachable
            }
        } else {
            ReturnState thenState = new ReturnState();
            tree.thenBranch().accept(this, thenState);
            thenReturns = thenState.returns;
            if (tree.elseBranch() != null) {
                ReturnState elseState = new ReturnState();
                tree.elseBranch().accept(this, elseState);
                elseReturns = elseState.returns;
            } else {
                elseReturns = false;
            }
        }
        data.returns = thenReturns && elseReturns;
        return Unit.INSTANCE;
    }

    // Try to constant-fold a boolean expression tree
    private Boolean tryConstantFold(ExpressionTree expr) {
        int notCount = 0;
        while (expr instanceof UnaryOperationTree u && u.operator() == edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType.LOGICAL_NOT) {
            notCount++;
            expr = u.operand();
        }
        Boolean value = null;
        if (expr instanceof BooleanLiteralTree b) {
            value = b.value();
        } else if (expr instanceof NegateTree n) {
            Boolean inner = tryConstantFold(n.expression());
            value = inner == null ? null : !inner;
        }
        if (value != null) {
            return (notCount % 2 == 0) ? value : !value;
        }
        return null;
    }

    @Override
    public Unit visit(WhileTree tree, ReturnState data) {
        tree.body().accept(this, data);
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ForTree tree, ReturnState data) {
        tree.body().accept(this, data);
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BreakTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ContinueTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(TernaryTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BooleanLiteralTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(NegateTree tree, ReturnState data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BlockTree tree, ReturnState data) {
        data.returns = false;
        for (var stmt : tree.statements()) {
            ReturnState stmtState = new ReturnState();
            stmt.accept(this, stmtState);
            if (stmtState.returns) {
                data.returns = true;
                break; // Dead code after return
            }
        }
        return Unit.INSTANCE;
    }
}
