package edu.kit.kastel.vads.compiler.semantic;

import edu.kit.kastel.vads.compiler.parser.ast.AssignmentTree;
import edu.kit.kastel.vads.compiler.parser.ast.DeclarationTree;
import edu.kit.kastel.vads.compiler.parser.ast.IdentExpressionTree;
import edu.kit.kastel.vads.compiler.parser.ast.LValueIdentTree;
import edu.kit.kastel.vads.compiler.parser.ast.NameTree;
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

import java.util.Locale;

/// Checks that variables are
/// - declared before assignment
/// - not declared twice
/// - not initialized twice
/// - assigned before referenced
class VariableStatusAnalysis implements NoOpVisitor<Namespace<VariableStatusAnalysis.VariableStatus>> {

    @Override
    public Unit visit(AssignmentTree assignmentTree, Namespace<VariableStatus> data) {
        switch (assignmentTree.lValue()) {
            case LValueIdentTree(var name) -> {
                VariableStatus status = data.get(name);
                checkDeclared(name, status);
                // Check for uninitialized use in compound assignments
                if (assignmentTree.operator().type() != edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType.ASSIGN) {
                    checkInitialized(name, status);
                }
                if (status != VariableStatus.INITIALIZED) {
                    // only update when needed, reassignment is totally fine
                    updateStatus(data, VariableStatus.INITIALIZED, name);
                }
            }
        }
        return NoOpVisitor.super.visit(assignmentTree, data);
    }

    private static void checkDeclared(NameTree name, VariableStatus status) {
        if (status == null) {
            throw new SemanticException("Variable " + name + " must be declared before assignment");
        }
    }

    private static void checkInitialized(NameTree name, VariableStatus status) {
        if (status == null || status == VariableStatus.DECLARED) {
            throw new SemanticException("Variable " + name + " must be initialized before use");
        }
    }

    private static void checkUndeclared(NameTree name, Namespace<VariableStatus> data) {
        if (data.containsKey(name)) {
            throw new SemanticException("Variable " + name + " is already declared");
        }
    }

    @Override
    public Unit visit(DeclarationTree declarationTree, Namespace<VariableStatus> data) {
        checkUndeclared(declarationTree.name(), data);
        VariableStatus status = declarationTree.initializer() == null
            ? VariableStatus.DECLARED
            : VariableStatus.INITIALIZED;
        updateStatus(data, status, declarationTree.name());
        return NoOpVisitor.super.visit(declarationTree, data);
    }

    private static void updateStatus(Namespace<VariableStatus> data, VariableStatus status, NameTree name) {
        data.put(name, status, (existing, replacement) -> {
            if (existing.ordinal() >= replacement.ordinal()) {
                throw new SemanticException("variable is already " + existing + ". Cannot be " + replacement + " here.");
            }
            return replacement;
        });
    }

    @Override
    public Unit visit(IdentExpressionTree identExpressionTree, Namespace<VariableStatus> data) {
        VariableStatus status = data.get(identExpressionTree.name());
        checkInitialized(identExpressionTree.name(), status);
        return NoOpVisitor.super.visit(identExpressionTree, data);
    }

    @Override
    public Unit visit(UnaryOperationTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(IfTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(WhileTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ForTree tree, Namespace<VariableStatus> data) {
        // Create a new scope for the for loop (already present, but ensure body and step are in this scope)
        Namespace<VariableStatus> loopScope = new Namespace<>(data);

        // Analyze initializer (may declare a new variable)
        if (tree.initializer() != null) {
            tree.initializer().accept(this, loopScope);
        }
        // Analyze condition
        if (tree.condition() != null) {
            tree.condition().accept(this, loopScope);
        }
        // Analyze body in the loop scope
        tree.body().accept(this, loopScope);
        // Analyze step in the loop scope
        if (tree.step() != null) {
            tree.step().accept(this, loopScope);
        }
        // Do NOT call super.visit(tree, data) to avoid double visitation
        return Unit.INSTANCE;
    }

    @Override
    public Unit visit(BreakTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(ContinueTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(TernaryTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(BooleanLiteralTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    @Override
    public Unit visit(NegateTree tree, Namespace<VariableStatus> data) {
        return NoOpVisitor.super.visit(tree, data);
    }

    enum VariableStatus {
        DECLARED,
        INITIALIZED;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static class VariableStatusPostorderVisitor extends edu.kit.kastel.vads.compiler.parser.visitor.RecursivePostorderVisitor<Namespace<VariableStatus>, Unit> {
        private final VariableStatusAnalysis customVisitor;
        public VariableStatusPostorderVisitor(VariableStatusAnalysis visitor) {
            super(visitor);
            this.customVisitor = visitor;
        }
        @Override
        public Unit visit(edu.kit.kastel.vads.compiler.parser.ast.ForTree tree, Namespace<VariableStatus> data) {
            // Only call the custom visitor's method, not the default traversal
            return customVisitor.visit(tree, data);
        }
    }

    public static VariableStatusPostorderVisitor makePostorderVisitor() {
        return new VariableStatusPostorderVisitor(new VariableStatusAnalysis());
    }
}
