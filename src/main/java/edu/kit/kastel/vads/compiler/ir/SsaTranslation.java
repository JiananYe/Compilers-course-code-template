package edu.kit.kastel.vads.compiler.ir;

import edu.kit.kastel.vads.compiler.ir.node.Block;
import edu.kit.kastel.vads.compiler.ir.node.DivNode;
import edu.kit.kastel.vads.compiler.ir.node.ModNode;
import edu.kit.kastel.vads.compiler.ir.node.Node;
import edu.kit.kastel.vads.compiler.ir.optimize.Optimizer;
import edu.kit.kastel.vads.compiler.ir.util.DebugInfo;
import edu.kit.kastel.vads.compiler.ir.util.DebugInfoHelper;
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
import edu.kit.kastel.vads.compiler.parser.ast.Tree;
import edu.kit.kastel.vads.compiler.parser.ast.TypeTree;
import edu.kit.kastel.vads.compiler.parser.ast.UnaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.IfTree;
import edu.kit.kastel.vads.compiler.parser.ast.WhileTree;
import edu.kit.kastel.vads.compiler.parser.ast.ForTree;
import edu.kit.kastel.vads.compiler.parser.ast.BreakTree;
import edu.kit.kastel.vads.compiler.parser.ast.ContinueTree;
import edu.kit.kastel.vads.compiler.parser.ast.TernaryTree;
import edu.kit.kastel.vads.compiler.parser.ast.BooleanLiteralTree;
import edu.kit.kastel.vads.compiler.parser.symbol.Name;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;

/// SSA translation as described in
/// [`Simple and Efficient Construction of Static Single Assignment Form`](https://compilers.cs.uni-saarland.de/papers/bbhlmz13cc.pdf).
///
/// This implementation also tracks side effect edges that can be used to avoid reordering of operations that cannot be
/// reordered.
///
/// We recommend to read the paper to better understand the mechanics implemented here.
public class SsaTranslation {
    private final FunctionTree function;
    private final GraphConstructor constructor;

    public SsaTranslation(FunctionTree function, Optimizer optimizer) {
        this.function = function;
        this.constructor = new GraphConstructor(optimizer, function.name().name().asString());
    }

    public IrGraph translate() {
        var visitor = new SsaTranslationVisitor();
        this.function.accept(visitor, this);
        return this.constructor.graph();
    }

    private void writeVariable(Name variable, Block block, Node value) {
        this.constructor.writeVariable(variable, block, value);
    }

    private Node readVariable(Name variable, Block block) {
        return this.constructor.readVariable(variable, block);
    }

    private Block currentBlock() {
        return this.constructor.currentBlock();
    }

    private static class SsaTranslationVisitor implements Visitor<SsaTranslation, Optional<Node>> {

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private static final Optional<Node> NOT_AN_EXPRESSION = Optional.empty();

        private final Deque<DebugInfo> debugStack = new ArrayDeque<>();

        private void pushSpan(Tree tree) {
            this.debugStack.push(DebugInfoHelper.getDebugInfo());
            DebugInfoHelper.setDebugInfo(new DebugInfo.SourceInfo(tree.span()));
        }

        private void popSpan() {
            DebugInfoHelper.setDebugInfo(this.debugStack.pop());
        }

        @Override
        public Optional<Node> visit(AssignmentTree assignmentTree, SsaTranslation data) {
            pushSpan(assignmentTree);
            BinaryOperator<Node> desugar = switch (assignmentTree.operator().type()) {
                case ASSIGN_MINUS -> data.constructor::newSub;
                case ASSIGN_PLUS -> data.constructor::newAdd;
                case ASSIGN_MUL -> data.constructor::newMul;
                case ASSIGN_DIV -> (lhs, rhs) -> projResultDivMod(data, data.constructor.newDiv(lhs, rhs));
                case ASSIGN_MOD -> (lhs, rhs) -> projResultDivMod(data, data.constructor.newMod(lhs, rhs));
                case ASSIGN -> null;
                default ->
                    throw new IllegalArgumentException("not an assignment operator " + assignmentTree.operator());
            };

            switch (assignmentTree.lValue()) {
                case LValueIdentTree(var name) -> {
                    Node rhs = assignmentTree.expression().accept(this, data).orElseThrow();
                    if (desugar != null) {
                        rhs = desugar.apply(data.readVariable(name.name(), data.currentBlock()), rhs);
                    }
                    data.writeVariable(name.name(), data.currentBlock(), rhs);
                }
            }
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(BinaryOperationTree binaryOperationTree, SsaTranslation data) {
            pushSpan(binaryOperationTree);
            Node lhs = binaryOperationTree.lhs().accept(this, data).orElseThrow();
            Node rhs = binaryOperationTree.rhs().accept(this, data).orElseThrow();
            Node res = switch (binaryOperationTree.operatorType()) {
                case MINUS -> data.constructor.newSub(lhs, rhs);
                case PLUS -> data.constructor.newAdd(lhs, rhs);
                case MUL -> data.constructor.newMul(lhs, rhs);
                case DIV -> projResultDivMod(data, data.constructor.newDiv(lhs, rhs));
                case MOD -> projResultDivMod(data, data.constructor.newMod(lhs, rhs));
                case GREATER -> data.constructor.newGreater(lhs, rhs);
                case GREATER_EQUAL -> data.constructor.newGreaterEqual(lhs, rhs);
                case LESS -> data.constructor.newLess(lhs, rhs);
                case LESS_EQUAL -> data.constructor.newLessEqual(lhs, rhs);
                case EQUAL -> data.constructor.newEqual(lhs, rhs);
                case NOT_EQUAL -> data.constructor.newNotEqual(lhs, rhs);
                default ->
                    throw new IllegalArgumentException("not a binary expression operator " + binaryOperationTree.operatorType());
            };
            popSpan();
            return Optional.of(res);
        }

        @Override
        public Optional<Node> visit(BlockTree blockTree, SsaTranslation data) {
            pushSpan(blockTree);
            for (StatementTree statement : blockTree.statements()) {
                statement.accept(this, data);
                // skip everything after a return in a block
                if (statement instanceof ReturnTree) {
                    break;
                }
            }
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(DeclarationTree declarationTree, SsaTranslation data) {
            pushSpan(declarationTree);
            if (declarationTree.initializer() != null) {
                Node rhs = declarationTree.initializer().accept(this, data).orElseThrow();
                data.writeVariable(declarationTree.name().name(), data.currentBlock(), rhs);
            }
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(FunctionTree functionTree, SsaTranslation data) {
            pushSpan(functionTree);
            Node start = data.constructor.newStart();
            data.constructor.writeCurrentSideEffect(data.constructor.newSideEffectProj(start));
            functionTree.body().accept(this, data);
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(IdentExpressionTree identExpressionTree, SsaTranslation data) {
            pushSpan(identExpressionTree);
            Node value = data.readVariable(identExpressionTree.name().name(), data.currentBlock());
            popSpan();
            return Optional.of(value);
        }

        @Override
        public Optional<Node> visit(LiteralTree literalTree, SsaTranslation data) {
            pushSpan(literalTree);
            Node node = data.constructor.newConstInt((int) literalTree.parseValue().orElseThrow());
            popSpan();
            return Optional.of(node);
        }

        @Override
        public Optional<Node> visit(LValueIdentTree lValueIdentTree, SsaTranslation data) {
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(NameTree nameTree, SsaTranslation data) {
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(NegateTree negateTree, SsaTranslation data) {
            pushSpan(negateTree);
            Node node = negateTree.expression().accept(this, data).orElseThrow();
            Node res = data.constructor.newSub(data.constructor.newConstInt(0), node);
            popSpan();
            return Optional.of(res);
        }

        @Override
        public Optional<Node> visit(UnaryOperationTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for UnaryOperationTree
            throw new UnsupportedOperationException("UnaryOperationTree SSA translation not yet implemented.");
        }

        @Override
        public Optional<Node> visit(ProgramTree programTree, SsaTranslation data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Node> visit(ReturnTree returnTree, SsaTranslation data) {
            pushSpan(returnTree);
            Node node = returnTree.expression().accept(this, data).orElseThrow();
            Node ret = data.constructor.newReturn(node);
            data.constructor.graph().endBlock().addPredecessor(ret);
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(TypeTree typeTree, SsaTranslation data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Node> visit(IfTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for IfTree
            throw new UnsupportedOperationException("IfTree SSA translation not yet implemented.");
        }

        @Override
        public Optional<Node> visit(WhileTree tree, SsaTranslation data) {
            pushSpan(tree);

            // 1. Create blocks for the loop header (condition), body, and exit
            Block condBlock = new Block(data.constructor.graph());
            Block bodyBlock = new Block(data.constructor.graph());
            Block exitBlock = new Block(data.constructor.graph());

            // 2. Jump to the condition block
            condBlock.addPredecessor(data.constructor.currentBlock());

            // 3. Evaluate the condition in the condBlock
            data.constructor.setCurrentBlock(condBlock);
            Node condValue = tree.condition().accept(this, data).orElseThrow();

            // 4. Branch on the condition: true -> body, false -> exit
            // Use a conditional jump node (assume newCondJump returns a node representing the branch)
            // If you have a specific node for this, use it; otherwise, just set up the blocks
            // For now, just connect the blocks as predecessors
            bodyBlock.addPredecessor(condBlock);
            exitBlock.addPredecessor(condBlock);
            // (In a real IR, you'd create a conditional jump node here)

            // 5. Body block: execute the body, then jump back to condition
            data.constructor.setCurrentBlock(bodyBlock);
            tree.body().accept(this, data);
            condBlock.addPredecessor(bodyBlock);

            // 6. Set the current block to the exit block for subsequent code
            data.constructor.setCurrentBlock(exitBlock);

            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(ForTree tree, SsaTranslation data) {
            pushSpan(tree);

            // 1. Translate the initializer (if present)
            if (tree.initializer() != null) {
                tree.initializer().accept(this, data);
            }

            // 2. Transform the body: insert step before every continue and at the end
            StatementTree transformedBody = insertStepBeforeContinueAndEnd(tree.body(), tree.step());

            // 3. Create a WhileTree with the condition and the transformed body
            WhileTree whileTree = new WhileTree(tree.condition(), transformedBody, tree.span());

            // 4. Visit the while loop
            whileTree.accept(this, data);

            popSpan();
            return NOT_AN_EXPRESSION;
        }

        // Helper to insert step before every continue and at the end of the body
        private StatementTree insertStepBeforeContinueAndEnd(StatementTree body, StatementTree step) {
            if (step == null) return body;

            if (body instanceof BlockTree block) {
                List<StatementTree> newStatements = new ArrayList<>();
                for (StatementTree stmt : block.statements()) {
                    newStatements.add(insertStepBeforeContinueAndEnd(stmt, step));
                }
                // Add step at the end if last statement is not return/break/continue
                if (newStatements.isEmpty() ||
                    !(isAbrupt(newStatements.get(newStatements.size() - 1)))) {
                    newStatements.add(step);
                }
                return new BlockTree(newStatements, block.span());
            } else if (body instanceof ContinueTree) {
                // Replace continue with step; continue
                List<StatementTree> seq = new ArrayList<>();
                seq.add(step);
                seq.add(body);
                return new BlockTree(seq, body.span());
            } else if (body instanceof IfTree ifTree) {
                StatementTree thenBranch = insertStepBeforeContinueAndEnd(ifTree.thenBranch(), step);
                StatementTree elseBranch = ifTree.elseBranch() != null
                    ? insertStepBeforeContinueAndEnd(ifTree.elseBranch(), step)
                    : null;
                return new IfTree(ifTree.condition(), thenBranch, elseBranch, ifTree.span());
            }
            // For other statements, just return as is
            return body;
        }

        // Helper to check if a statement is abrupt (return, break, continue)
        private boolean isAbrupt(StatementTree stmt) {
            return stmt instanceof ReturnTree || stmt instanceof BreakTree || stmt instanceof ContinueTree;
        }

        @Override
        public Optional<Node> visit(BreakTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for BreakTree
            throw new UnsupportedOperationException("BreakTree SSA translation not yet implemented.");
        }

        @Override
        public Optional<Node> visit(ContinueTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for ContinueTree
            throw new UnsupportedOperationException("ContinueTree SSA translation not yet implemented.");
        }

        @Override
        public Optional<Node> visit(TernaryTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for TernaryTree
            throw new UnsupportedOperationException("TernaryTree SSA translation not yet implemented.");
        }

        @Override
        public Optional<Node> visit(BooleanLiteralTree tree, SsaTranslation data) {
            // TODO: Implement SSA translation for BooleanLiteralTree
            throw new UnsupportedOperationException("BooleanLiteralTree SSA translation not yet implemented.");
        }

        private Node projResultDivMod(SsaTranslation data, Node divMod) {
            // make sure we actually have a div or a mod, as optimizations could
            // have changed it to something else already
            if (!(divMod instanceof DivNode || divMod instanceof ModNode)) {
                return divMod;
            }
            Node projSideEffect = data.constructor.newSideEffectProj(divMod);
            data.constructor.writeCurrentSideEffect(projSideEffect);
            return data.constructor.newResultProj(divMod);
        }
    }


}
