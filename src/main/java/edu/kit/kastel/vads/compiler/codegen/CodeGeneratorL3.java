package edu.kit.kastel.vads.compiler.codegen;

import edu.kit.kastel.vads.compiler.parser.ast.*;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;
import edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType;

import java.util.*;

public class CodeGeneratorL3 implements Visitor<Void, String> {
    private final StringBuilder code = new StringBuilder();
    private final Map<String, Integer> variables = new HashMap<>();
    private final Stack<Integer> stack = new Stack<>();
    private int labelCounter = 0;
    private int stackOffset = 0;
    private final Stack<String> loopStartLabels = new Stack<>();
    private final Stack<String> loopEndLabels = new Stack<>();
    private final List<String> paramRegisters = List.of("%rdi", "%rsi", "%rdx", "%rcx", "%r8", "%r9");
    private boolean hasReturn = false;

    public CodeGeneratorL3() {
        code.append("    .section .text\n");
    }

    public String getCode() {
        return code.toString();
    }

    private String newLabel(String prefix) {
        return prefix + labelCounter++;
    }

    private void push(String reg) {
        code.append("    pushq ").append(reg).append("\n");
        stack.push(stackOffset);
        stackOffset += 8;
    }

    private void pop(String reg) {
        code.append("    popq ").append(reg).append("\n");
        stack.pop();
        stackOffset -= 8;
    }

    @Override
    public String visit(ProgramTree tree, Void data) {
        for (FunctionTree f : tree.topLevelTrees()) {
            code.append("    .global ").append(f.name().name().asString()).append("\n");
            code.append(f.name().name().asString()).append(":\n");
            f.accept(this, data);
        }
        return null;
    }

    @Override
    public String visit(FunctionTree tree, Void data) {
        hasReturn = false;
        code.append("    pushq %rbp\n");
        code.append("    movq %rsp, %rbp\n");
        int paramCount = tree.parameters().size();
        int localOffset = 8;
        // Assign parameters to stack slots
        for (int i = 0; i < paramCount; i++) {
            String paramName = tree.parameters().get(i).name().name().asString();
            variables.put(paramName, localOffset);
            code.append("    movq ").append(paramRegisters.get(i)).append(", -").append(localOffset).append("(%rbp)\n");
            localOffset += 8;
        }
        stackOffset = localOffset;
        tree.body().accept(this, data);
        if (!hasReturn) {
            code.append("    movq %rbp, %rsp\n");
            code.append("    popq %rbp\n");
            code.append("    ret\n");
        }
        variables.clear();
        stackOffset = 0;
        return null;
    }

    @Override
    public String visit(BlockTree tree, Void data) {
        for (StatementTree stmt : tree.statements()) {
            stmt.accept(this, data);
        }
        return null;
    }

    @Override
    public String visit(DeclarationTree tree, Void data) {
        String name = tree.name().name().asString();
        variables.put(name, stackOffset);
        stackOffset += 8;
        if (tree.initializer() != null) {
            tree.initializer().accept(this, data);
            code.append("    movq %rax, -").append(variables.get(name)).append("(%rbp)\n");
        }
        return null;
    }

    @Override
    public String visit(AssignmentTree tree, Void data) {
        String name = ((LValueIdentTree) tree.lValue()).name().name().asString();
        Integer offset = variables.get(name);
        if (tree.operator().type() == OperatorType.ASSIGN) {
            tree.expression().accept(this, data);
        } else {
            tree.expression().accept(this, data);
            push("%rax");
            code.append("    movq -").append(offset).append("(%rbp), %rax\n");
            pop("%rcx");
            switch (tree.operator().type()) {
                case ASSIGN_PLUS -> code.append("    addq %rcx, %rax\n");
                case ASSIGN_MINUS -> code.append("    subq %rcx, %rax\n");
                case ASSIGN_MUL -> code.append("    imulq %rcx, %rax\n");
                case ASSIGN_DIV -> { code.append("    cqto\n"); code.append("    idivq %rcx\n"); }
                case ASSIGN_MOD -> { code.append("    cqto\n"); code.append("    idivq %rcx\n"); code.append("    movq %rdx, %rax\n"); }
                case ASSIGN_BIT_AND -> code.append("    andq %rcx, %rax\n");
                case ASSIGN_BIT_OR -> code.append("    orq %rcx, %rax\n");
                case ASSIGN_BIT_XOR -> code.append("    xorq %rcx, %rax\n");
                case ASSIGN_SHIFT_LEFT -> { code.append("    movb %cl, %cl\n"); code.append("    shlq %cl, %rax\n"); }
                case ASSIGN_SHIFT_RIGHT -> { code.append("    movb %cl, %cl\n"); code.append("    sarq %cl, %rax\n"); }
                default -> throw new UnsupportedOperationException("Unsupported assignment operator: " + tree.operator().type());
            }
        }
        code.append("    movq %rax, -").append(offset).append("(%rbp)\n");
        return null;
    }

    @Override
    public String visit(ReturnTree tree, Void data) {
        tree.expression().accept(this, data);
        code.append("    movq %rbp, %rsp\n");
        code.append("    popq %rbp\n");
        code.append("    ret\n");
        hasReturn = true;
        return null;
    }

    @Override
    public String visit(IfTree tree, Void data) {
        String elseLabel = newLabel("else");
        String endLabel = newLabel("endif");
        tree.condition().accept(this, data);
        code.append("    testq %rax, %rax\n");
        code.append("    jz ").append(elseLabel).append("\n");
        tree.thenBranch().accept(this, data);
        code.append("    jmp ").append(endLabel).append("\n");
        code.append(elseLabel).append(":\n");
        if (tree.elseBranch() != null) {
            tree.elseBranch().accept(this, data);
        }
        code.append(endLabel).append(":\n");
        return null;
    }

    @Override
    public String visit(WhileTree tree, Void data) {
        String startLabel = newLabel("while_start");
        String endLabel = newLabel("while_end");
        loopStartLabels.push(startLabel);
        loopEndLabels.push(endLabel);
        code.append(startLabel).append(":\n");
        tree.condition().accept(this, data);
        code.append("    testq %rax, %rax\n");
        code.append("    jz ").append(endLabel).append("\n");
        tree.body().accept(this, data);
        code.append("    jmp ").append(startLabel).append("\n");
        code.append(endLabel).append(":\n");
        loopStartLabels.pop();
        loopEndLabels.pop();
        return null;
    }

    @Override
    public String visit(ForTree tree, Void data) {
        String startLabel = newLabel("for_start");
        String endLabel = newLabel("for_end");
        loopStartLabels.push(startLabel);
        loopEndLabels.push(endLabel);
        if (tree.initializer() != null) {
            tree.initializer().accept(this, data);
        }
        code.append(startLabel).append(":\n");
        tree.condition().accept(this, data);
        code.append("    testq %rax, %rax\n");
        code.append("    jz ").append(endLabel).append("\n");
        tree.body().accept(this, data);
        if (tree.step() != null) {
            tree.step().accept(this, data);
        }
        code.append("    jmp ").append(startLabel).append("\n");
        code.append(endLabel).append(":\n");
        loopStartLabels.pop();
        loopEndLabels.pop();
        return null;
    }

    @Override
    public String visit(BreakTree tree, Void data) {
        code.append("    jmp ").append(loopEndLabels.peek()).append("\n");
        return null;
    }

    @Override
    public String visit(ContinueTree tree, Void data) {
        code.append("    jmp ").append(loopStartLabels.peek()).append("\n");
        return null;
    }

    @Override
    public String visit(NegateTree tree, Void data) {
        tree.expression().accept(this, data);
        code.append("    negq %rax\n");
        return null;
    }

    @Override
    public String visit(BinaryOperationTree tree, Void data) {
        tree.rhs().accept(this, data);
        push("%rax");
        tree.lhs().accept(this, data);
        pop("%rcx");
        switch (tree.operatorType()) {
            case OperatorType.PLUS -> code.append("    addq %rcx, %rax\n");
            case OperatorType.MINUS -> { code.append("    subq %rcx, %rax\n"); code.append("    negq %rax\n"); }
            case OperatorType.MUL -> code.append("    imulq %rcx, %rax\n");
            case OperatorType.DIV -> { code.append("    cqto\n"); code.append("    idivq %rcx\n"); }
            case OperatorType.MOD -> { code.append("    cqto\n"); code.append("    idivq %rcx\n"); code.append("    movq %rdx, %rax\n"); }
            case OperatorType.BIT_AND -> code.append("    andq %rcx, %rax\n");
            case OperatorType.BIT_OR -> code.append("    orq %rcx, %rax\n");
            case OperatorType.BIT_XOR -> code.append("    xorq %rcx, %rax\n");
            case OperatorType.SHIFT_LEFT -> { code.append("    movb %cl, %cl\n"); code.append("    shlq %cl, %rax\n"); }
            case OperatorType.SHIFT_RIGHT -> { code.append("    movb %cl, %cl\n"); code.append("    sarq %cl, %rax\n"); }
            case OperatorType.EQUAL -> { code.append("    cmpq %rcx, %rax\n"); code.append("    sete %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.NOT_EQUAL -> { code.append("    cmpq %rcx, %rax\n"); code.append("    setne %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.LESS -> { code.append("    cmpq %rcx, %rax\n"); code.append("    setl %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.LESS_EQUAL -> { code.append("    cmpq %rcx, %rax\n"); code.append("    setle %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.GREATER -> { code.append("    cmpq %rcx, %rax\n"); code.append("    setg %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.GREATER_EQUAL -> { code.append("    cmpq %rcx, %rax\n"); code.append("    setge %al\n"); code.append("    movzbq %al, %rax\n"); }
            case OperatorType.LOGICAL_AND -> {
                String falseLabel = newLabel("and_false");
                String endLabel = newLabel("and_end");
                code.append("    testq %rax, %rax\n");
                code.append("    jz ").append(falseLabel).append("\n");
                code.append("    testq %rcx, %rcx\n");
                code.append("    jz ").append(falseLabel).append("\n");
                code.append("    movq $1, %rax\n");
                code.append("    jmp ").append(endLabel).append("\n");
                code.append(falseLabel).append(":\n");
                code.append("    xorq %rax, %rax\n");
                code.append(endLabel).append(":\n");
            }
            case OperatorType.LOGICAL_OR -> {
                String trueLabel = newLabel("or_true");
                String endLabel = newLabel("or_end");
                code.append("    testq %rax, %rax\n");
                code.append("    jnz ").append(trueLabel).append("\n");
                code.append("    testq %rcx, %rcx\n");
                code.append("    jnz ").append(trueLabel).append("\n");
                code.append("    xorq %rax, %rax\n");
                code.append("    jmp ").append(endLabel).append("\n");
                code.append(trueLabel).append(":\n");
                code.append("    movq $1, %rax\n");
                code.append(endLabel).append(":\n");
            }
        }
        return null;
    }

    @Override
    public String visit(UnaryOperationTree tree, Void data) {
        tree.operand().accept(this, data);
        switch (tree.operator()) {
            case OperatorType.MINUS -> code.append("    negq %rax\n");
            case OperatorType.BIT_NOT -> code.append("    notq %rax\n");
            case OperatorType.LOGICAL_NOT -> { code.append("    testq %rax, %rax\n"); code.append("    setz %al\n"); code.append("    movzbq %al, %rax\n"); }
        }
        return null;
    }

    @Override
    public String visit(TernaryTree tree, Void data) {
        String falseLabel = newLabel("ternary_false");
        String endLabel = newLabel("ternary_end");
        tree.condition().accept(this, data);
        code.append("    testq %rax, %rax\n");
        code.append("    jz ").append(falseLabel).append("\n");
        tree.thenExpr().accept(this, data);
        code.append("    jmp ").append(endLabel).append("\n");
        code.append(falseLabel).append(":\n");
        tree.elseExpr().accept(this, data);
        code.append(endLabel).append(":\n");
        return null;
    }

    @Override
    public String visit(BooleanLiteralTree tree, Void data) {
        code.append("    movq $").append(tree.value() ? "1" : "0").append(", %rax\n");
        return null;
    }

    @Override
    public String visit(IdentExpressionTree tree, Void data) {
        String name = tree.name().name().asString();
        code.append("    movq -").append(variables.get(name)).append("(%rbp), %rax\n");
        return null;
    }

    @Override
    public String visit(LiteralTree tree, Void data) {
        code.append("    movq $").append(tree.value()).append(", %rax\n");
        return null;
    }

    @Override
    public String visit(LValueIdentTree tree, Void data) {
        return null;
    }

    @Override
    public String visit(NameTree tree, Void data) {
        return null;
    }

    @Override
    public String visit(TypeTree tree, Void data) {
        return null;
    }

    @Override
    public String visit(CallExpressionTree tree, Void data) {
        String callee = tree.callee().name().asString();
        // Built-in functions
        if (callee.equals("print")) {
            tree.arguments().get(0).accept(this, data);
            code.append("    movq %rax, %rdi\n");
            code.append("    call putchar\n");
            code.append("    movq $0, %rax\n");
        } else if (callee.equals("read")) {
            code.append("    call getchar\n");
            code.append("    movq %rax, %rax\n");
        } else if (callee.equals("flush")) {
            code.append("    call fflush\n");
            code.append("    movq $0, %rax\n");
        } else {
            // Evaluate arguments right-to-left (System V ABI)
            int argCount = tree.arguments().size();
            for (int i = argCount - 1; i >= 0; i--) {
                tree.arguments().get(i).accept(this, data);
                if (i < paramRegisters.size()) {
                    code.append("    movq %rax, ").append(paramRegisters.get(i)).append("\n");
                } else {
                    // More than 6 args: push on stack (not handled here)
                }
            }
            code.append("    call ").append(callee).append("\n");
        }
        return null;
    }
} 