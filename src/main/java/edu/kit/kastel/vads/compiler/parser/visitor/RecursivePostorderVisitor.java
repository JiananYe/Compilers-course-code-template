package edu.kit.kastel.vads.compiler.parser.visitor;

import edu.kit.kastel.vads.compiler.parser.ast.AssignmentTree;
import edu.kit.kastel.vads.compiler.parser.ast.BinaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.BlockTree;
import edu.kit.kastel.vads.compiler.parser.ast.DeclarationTree;
import edu.kit.kastel.vads.compiler.parser.ast.FunctionTree;
import edu.kit.kastel.vads.compiler.parser.ast.IdentExpressionTree;
import edu.kit.kastel.vads.compiler.parser.ast.LValueIdentTree;
import edu.kit.kastel.vads.compiler.parser.ast.LiteralTree;
import edu.kit.kastel.vads.compiler.parser.ast.NameTree;
import edu.kit.kastel.vads.compiler.parser.ast.NegateTree;
import edu.kit.kastel.vads.compiler.parser.ast.ProgramTree;
import edu.kit.kastel.vads.compiler.parser.ast.ReturnTree;
import edu.kit.kastel.vads.compiler.parser.ast.StatementTree;
import edu.kit.kastel.vads.compiler.parser.ast.TypeTree;
import edu.kit.kastel.vads.compiler.parser.ast.UnaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.IfTree;
import edu.kit.kastel.vads.compiler.parser.ast.WhileTree;
import edu.kit.kastel.vads.compiler.parser.ast.ForTree;
import edu.kit.kastel.vads.compiler.parser.ast.BreakTree;
import edu.kit.kastel.vads.compiler.parser.ast.ContinueTree;
import edu.kit.kastel.vads.compiler.parser.ast.TernaryTree;
import edu.kit.kastel.vads.compiler.parser.ast.BooleanLiteralTree;
import edu.kit.kastel.vads.compiler.parser.ast.CallExpressionTree;

/// A visitor that traverses a tree in postorder
/// @param <T> a type for additional data
/// @param <R> a type for a return type
public class RecursivePostorderVisitor<T, R> implements Visitor<T, R> {
    private final Visitor<T, R> visitor;

    public RecursivePostorderVisitor(Visitor<T, R> visitor) {
        this.visitor = visitor;
    }

    @Override
    public R visit(AssignmentTree assignmentTree, T data) {
        R r = assignmentTree.lValue().accept(this, data);
        r = assignmentTree.expression().accept(this, accumulate(data, r));
        r = this.visitor.visit(assignmentTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(BinaryOperationTree binaryOperationTree, T data) {
        R r = binaryOperationTree.lhs().accept(this, data);
        r = binaryOperationTree.rhs().accept(this, accumulate(data, r));
        r = this.visitor.visit(binaryOperationTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(BlockTree blockTree, T data) {
        R r;
        T d = data;
        for (StatementTree statement : blockTree.statements()) {
            r = statement.accept(this, d);
            d = accumulate(d, r);
        }
        r = this.visitor.visit(blockTree, d);
        return r;
    }

    @Override
    public R visit(DeclarationTree declarationTree, T data) {
        R r = declarationTree.type().accept(this, data);
        r = declarationTree.name().accept(this, accumulate(data, r));
        if (declarationTree.initializer() != null) {
            r = declarationTree.initializer().accept(this, accumulate(data, r));
        }
        r = this.visitor.visit(declarationTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(FunctionTree functionTree, T data) {
        R r = functionTree.returnType().accept(this, data);
        r = functionTree.name().accept(this, accumulate(data, r));
        r = functionTree.body().accept(this, accumulate(data, r));
        r = this.visitor.visit(functionTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(IdentExpressionTree identExpressionTree, T data) {
        R r = identExpressionTree.name().accept(this, data);
        r = this.visitor.visit(identExpressionTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(LiteralTree literalTree, T data) {
        return this.visitor.visit(literalTree, data);
    }

    @Override
    public R visit(LValueIdentTree lValueIdentTree, T data) {
        R r = lValueIdentTree.name().accept(this, data);
        r = this.visitor.visit(lValueIdentTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(NameTree nameTree, T data) {
        return this.visitor.visit(nameTree, data);
    }

    @Override
    public R visit(NegateTree negateTree, T data) {
        R r = negateTree.expression().accept(this, data);
        r = this.visitor.visit(negateTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(ProgramTree programTree, T data) {
        R r;
        T d = data;
        for (FunctionTree tree : programTree.topLevelTrees()) {
            r = tree.accept(this, d);
            d = accumulate(data, r);
        }
        r = this.visitor.visit(programTree, d);
        return r;
    }

    @Override
    public R visit(ReturnTree returnTree, T data) {
        R r = returnTree.expression().accept(this, data);
        r = this.visitor.visit(returnTree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(TypeTree typeTree, T data) {
        return this.visitor.visit(typeTree, data);
    }

    @Override
    public R visit(UnaryOperationTree tree, T data) {
        R r = tree.operand().accept(this, data);
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(IfTree tree, T data) {
        R r = tree.condition().accept(this, data);
        r = tree.thenBranch().accept(this, accumulate(data, r));
        if (tree.elseBranch() != null) {
            r = tree.elseBranch().accept(this, accumulate(data, r));
        }
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(WhileTree tree, T data) {
        R r = tree.condition().accept(this, data);
        r = tree.body().accept(this, accumulate(data, r));
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(ForTree tree, T data) {
        R r = null;
        if (tree.initializer() != null) {
            r = tree.initializer().accept(this, data);
        } else {
            r = data == null ? null : (R) data;
        }
        r = tree.condition().accept(this, accumulate(data, r));
        if (tree.step() != null) {
            r = tree.step().accept(this, accumulate(data, r));
        }
        if (tree.body() != null) {
            r = tree.body().accept(this, accumulate(data, r));
        }
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(BreakTree tree, T data) {
        return this.visitor.visit(tree, data);
    }

    @Override
    public R visit(ContinueTree tree, T data) {
        return this.visitor.visit(tree, data);
    }

    @Override
    public R visit(TernaryTree tree, T data) {
        R r = tree.condition().accept(this, data);
        r = tree.thenExpr().accept(this, accumulate(data, r));
        r = tree.elseExpr().accept(this, accumulate(data, r));
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    @Override
    public R visit(BooleanLiteralTree tree, T data) {
        return this.visitor.visit(tree, data);
    }

    @Override
    public R visit(CallExpressionTree tree, T data) {
        R r = tree.callee().accept(this, data);
        for (var arg : tree.arguments()) {
            r = arg.accept(this, accumulate(data, r));
        }
        r = this.visitor.visit(tree, accumulate(data, r));
        return r;
    }

    protected T accumulate(T data, R value) {
        return data;
    }
}
