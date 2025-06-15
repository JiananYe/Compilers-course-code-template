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
import edu.kit.kastel.vads.compiler.ir.node.Phi;

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
        Node value = this.constructor.readVariable(variable, block);
        return value;
    }

    private Block currentBlock() {
        return this.constructor.currentBlock();
    }

    private static class SsaTranslationVisitor implements Visitor<SsaTranslation, Optional<Node>> {

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private static final Optional<Node> NOT_AN_EXPRESSION = Optional.empty();

        private final Deque<DebugInfo> debugStack = new ArrayDeque<>();
        private final Deque<Block> breakTargets = new ArrayDeque<>();
        private final Deque<Block> continueTargets = new ArrayDeque<>();

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
                case ASSIGN_SHIFT_LEFT -> data.constructor::newShl;
                case ASSIGN_SHIFT_RIGHT -> data.constructor::newShr;
                case ASSIGN_BIT_AND -> data.constructor::newAnd;
                case ASSIGN_BIT_XOR -> data.constructor::newXor;
                case ASSIGN_BIT_OR -> data.constructor::newOr;
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
                case BIT_OR -> data.constructor.newOr(lhs, rhs);
                case BIT_AND -> data.constructor.newAnd(lhs, rhs);
                case BIT_XOR -> data.constructor.newXor(lhs, rhs);
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
            pushSpan(tree);
            Node operand = tree.operand().accept(this, data).orElseThrow();
            Node result = switch (tree.operator()) {
                case LOGICAL_NOT -> data.constructor.newEqual(operand, data.constructor.newConstInt(0));
                case BIT_NOT -> data.constructor.newNot(operand);
                default -> throw new IllegalArgumentException("Unsupported unary operator: " + tree.operator());
            };
            popSpan();
            return Optional.of(result);
        }

        @Override
        public Optional<Node> visit(ProgramTree programTree, SsaTranslation data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Node> visit(ReturnTree returnTree, SsaTranslation data) {
            pushSpan(returnTree);
            Node value = returnTree.expression().accept(this, data).orElseThrow();
            Node ret = data.constructor.newReturn(value);
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
            pushSpan(tree);

            // Evaluate the condition
            Node condValue = tree.condition().accept(this, data).orElseThrow();

            // Create blocks for then, else, and merge
            Block thenBlock = new Block(data.constructor.graph());
            Block elseBlock = new Block(data.constructor.graph());
            Block mergeBlock = new Block(data.constructor.graph());

            // Branch on the condition
            thenBlock.addPredecessor(data.constructor.currentBlock());
            elseBlock.addPredecessor(data.constructor.currentBlock());

            // Then branch
            data.constructor.setCurrentBlock(thenBlock);
            tree.thenBranch().accept(this, data);
            mergeBlock.addPredecessor(thenBlock);

            // Else branch
            if (tree.elseBranch() != null) {
                data.constructor.setCurrentBlock(elseBlock);
                tree.elseBranch().accept(this, data);
                mergeBlock.addPredecessor(elseBlock);
            } else {
                // If no else, just connect elseBlock to mergeBlock
                mergeBlock.addPredecessor(elseBlock);
            }

            // Merge
            data.constructor.setCurrentBlock(mergeBlock);

            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(WhileTree tree, SsaTranslation data) {
            pushSpan(tree);

            Block condBlock = new Block(data.constructor.graph());
            Block bodyBlock = new Block(data.constructor.graph());
            Block exitBlock = new Block(data.constructor.graph());

            condBlock.addPredecessor(data.constructor.currentBlock());
            data.constructor.setCurrentBlock(condBlock);

            // Push loop targets
            continueTargets.push(condBlock);
            breakTargets.push(exitBlock);

            Node condValue = tree.condition().accept(this, data).orElseThrow();
            bodyBlock.addPredecessor(condBlock);
            exitBlock.addPredecessor(condBlock);

            data.constructor.setCurrentBlock(bodyBlock);
            tree.body().accept(this, data);
            condBlock.addPredecessor(bodyBlock);

            // Pop loop targets
            continueTargets.pop();
            breakTargets.pop();

            data.constructor.setCurrentBlock(exitBlock);
            // Seal the exit block to finalize Phi nodes
            data.constructor.sealBlock(exitBlock);

            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(ForTree tree, SsaTranslation data) {
            pushSpan(tree);

            // 1. Collect all variables assigned in the loop
            java.util.Set<edu.kit.kastel.vads.compiler.parser.symbol.Name> assignedVars = new java.util.HashSet<>();
            Visitor<SsaTranslation, Void> assignmentTracker = new Visitor<>() {
                @Override public Void visit(AssignmentTree t, SsaTranslation d) {
                    if (t.lValue() instanceof LValueIdentTree lval) {
                        assignedVars.add(lval.name().name());
                    }
                    return null;
                }
                @Override public Void visit(DeclarationTree t, SsaTranslation d) { return null; }
                @Override public Void visit(BlockTree t, SsaTranslation d) { for (StatementTree s : t.statements()) s.accept(this, d); return null; }
                @Override public Void visit(IfTree t, SsaTranslation d) { t.thenBranch().accept(this, d); if (t.elseBranch() != null) t.elseBranch().accept(this, d); return null; }
                @Override public Void visit(WhileTree t, SsaTranslation d) { t.body().accept(this, d); return null; }
                @Override public Void visit(ForTree t, SsaTranslation d) { t.body().accept(this, d); return null; }
                @Override public Void visit(ContinueTree t, SsaTranslation d) { return null; }
                @Override public Void visit(BreakTree t, SsaTranslation d) { return null; }
                @Override public Void visit(ReturnTree t, SsaTranslation d) { return null; }
                @Override public Void visit(BinaryOperationTree t, SsaTranslation d) { return null; }
                @Override public Void visit(NegateTree t, SsaTranslation d) { return null; }
                @Override public Void visit(UnaryOperationTree t, SsaTranslation d) { return null; }
                @Override public Void visit(IdentExpressionTree t, SsaTranslation d) { return null; }
                @Override public Void visit(LiteralTree t, SsaTranslation d) { return null; }
                @Override public Void visit(BooleanLiteralTree t, SsaTranslation d) { return null; }
                @Override public Void visit(TernaryTree t, SsaTranslation d) { return null; }
                @Override public Void visit(LValueIdentTree t, SsaTranslation d) { return null; }
                @Override public Void visit(NameTree t, SsaTranslation d) { return null; }
                @Override public Void visit(TypeTree t, SsaTranslation d) { return null; }
                @Override public Void visit(FunctionTree t, SsaTranslation d) { return null; }
                @Override public Void visit(ProgramTree t, SsaTranslation d) { return null; }
            };
            tree.body().accept(assignmentTracker, data);
            if (tree.step() != null) tree.step().accept(assignmentTracker, data);

            // 2. Translate the initializer (if present)
            if (tree.initializer() != null) {
                tree.initializer().accept(this, data);
            }

            // 3. Create explicit loop header block
            Block preLoopBlock = data.currentBlock();
            Block loopHeader = new Block(data.constructor.graph());
            loopHeader.addPredecessor(preLoopBlock);
            data.constructor.setCurrentBlock(loopHeader);

            // 4. Insert Phi nodes for all assigned variables
            java.util.Map<edu.kit.kastel.vads.compiler.parser.symbol.Name, Phi> phiNodes = new java.util.HashMap<>();
            for (var var : assignedVars) {
                Node initVal = data.readVariable(var, preLoopBlock);
                Phi phi = data.constructor.graph().startBlock() == loopHeader ? null : data.constructor.newPhi();
                if (phi != null) {
                    phi.appendOperand(initVal); // incoming from before loop
                    data.writeVariable(var, loopHeader, phi);
                    phiNodes.put(var, phi);
                }
            }

            // 5. Transform the body: insert step before every continue and at the end
            StatementTree transformedBody = insertStepBeforeContinueAndEnd(tree.body(), tree.step());

            // 6. Create loop body and exit blocks
            Block bodyBlock = new Block(data.constructor.graph());
            Block exitBlock = new Block(data.constructor.graph());

            // 7. Evaluate condition in loop header
            Node condValue = tree.condition().accept(this, data).orElseThrow();
            bodyBlock.addPredecessor(loopHeader);
            exitBlock.addPredecessor(loopHeader);

            // 8. Loop body
            data.constructor.setCurrentBlock(bodyBlock);
            transformedBody.accept(this, data);

            // After the loop body, update phi variable mapping for the backedge
            for (var entry : phiNodes.entrySet()) {
                data.writeVariable(entry.getKey(), loopHeader, data.readVariable(entry.getKey(), bodyBlock));
            }

            loopHeader.addPredecessor(bodyBlock); // backedge

            // 9. After loop, set current block to exit
            data.constructor.setCurrentBlock(exitBlock);

            // 10. Seal blocks
            data.constructor.sealBlock(loopHeader);
            data.constructor.sealBlock(bodyBlock);
            data.constructor.sealBlock(exitBlock);

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
            pushSpan(tree);
            Block target = breakTargets.peek();
            if (target == null) {
                throw new IllegalStateException("break used outside of loop");
            }
            target.addPredecessor(data.constructor.currentBlock());
            // End the current block (no further code should be generated here)
            data.constructor.setCurrentBlock(new Block(data.constructor.graph()));
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(ContinueTree tree, SsaTranslation data) {
            pushSpan(tree);
            Block target = continueTargets.peek();
            if (target == null) {
                throw new IllegalStateException("continue used outside of loop");
            }
            target.addPredecessor(data.constructor.currentBlock());
            // End the current block (no further code should be generated here)
            data.constructor.setCurrentBlock(new Block(data.constructor.graph()));
            popSpan();
            return NOT_AN_EXPRESSION;
        }

        @Override
        public Optional<Node> visit(TernaryTree tree, SsaTranslation data) {
            pushSpan(tree);

            // Evaluate the condition
            Node condValue = tree.condition().accept(this, data).orElseThrow();

            // Create blocks for then, else, and merge
            Block thenBlock = new Block(data.constructor.graph());
            Block elseBlock = new Block(data.constructor.graph());
            Block mergeBlock = new Block(data.constructor.graph());

            // Branch on the condition
            thenBlock.addPredecessor(data.constructor.currentBlock());
            elseBlock.addPredecessor(data.constructor.currentBlock());

            // Then branch
            data.constructor.setCurrentBlock(thenBlock);
            Node thenValue = tree.thenExpr().accept(this, data).orElseThrow();
            mergeBlock.addPredecessor(thenBlock);

            // Else branch
            data.constructor.setCurrentBlock(elseBlock);
            Node elseValue = tree.elseExpr().accept(this, data).orElseThrow();
            mergeBlock.addPredecessor(elseBlock);

            // Merge
            data.constructor.setCurrentBlock(mergeBlock);
            Node phi = data.constructor.newPhi();
            ((Phi) phi).appendOperand(thenValue);
            ((Phi) phi).appendOperand(elseValue);

            popSpan();
            return Optional.of(phi);
        }

        @Override
        public Optional<Node> visit(BooleanLiteralTree tree, SsaTranslation data) {
            pushSpan(tree);
            Node node = data.constructor.newConstInt(tree.value() ? 1 : 0);
            popSpan();
            return Optional.of(node);
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
