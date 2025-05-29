package edu.kit.kastel.vads.compiler.codegen;

import edu.kit.kastel.vads.compiler.parser.ast.*;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;
import edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class CodeGenerator implements Visitor<Void, String> {
    private final StringBuilder code = new StringBuilder();
    private final Map<String, Integer> variables = new HashMap<>();
    private final Stack<Integer> stack = new Stack<>();
    private int labelCounter = 0;
    private int stackOffset = 0;
    private final Stack<String> loopStartLabels = new Stack<>();
    private final Stack<String> loopEndLabels = new Stack<>();

    public CodeGenerator() {
        // Initialize the stack frame
        code.append("    .section .text\n");
        code.append("    .global main\n");
        code.append("    .global _main\n");
        code.append("main:\n");
        code.append("    call _main\n");
        code.append("    movq %rax, %rdi\n");
        code.append("    movq $0x3C, %rax\n");
        code.append("    syscall\n");
        code.append("_main:\n");
        code.append("    pushq %rbp\n");
        code.append("    movq %rsp, %rbp\n");
        code.append("    subq $1024, %rsp\n");
        code.append("    pushq %rbx\n");
        code.append("    pushq %rcx\n");
        code.append("    pushq %rdx\n");
    }

    public String getCode() {
        // Clean up the stack frame
        code.append("    popq %rdx\n");            // Restore callee-saved registers
        code.append("    popq %rcx\n");
        code.append("    popq %rbx\n");
        code.append("    movq %rbp, %rsp\n");      // Restore stack pointer
        code.append("    popq %rbp\n");            // Restore base pointer
        code.append("    ret\n");                   // Return with result in %rax
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
        tree.functions().forEach(f -> f.accept(this, data));
        return null;
    }

    @Override
    public String visit(FunctionTree tree, Void data) {
        tree.body().accept(this, data);
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
        String name = tree.name().name().identifier();
        variables.put(name, stackOffset);
        stackOffset += 8;  // Allocate space for the variable
        
        if (tree.initializer() != null) {
            tree.initializer().accept(this, data);
            code.append("    movq %rax, -").append(variables.get(name)).append("(%rbp)\n");
        }
        return null;
    }

    @Override
    public String visit(AssignmentTree tree, Void data) {
        tree.expression().accept(this, data);
        String name = ((LValueIdentTree) tree.lvalue()).name().name().identifier();
        code.append("    movq %rax, -").append(variables.get(name)).append("(%rbp)\n");
        return null;
    }

    @Override
    public String visit(ReturnTree tree, Void data) {
        tree.expression().accept(this, data);
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
    public String visit(BinaryOperationTree tree, Void data) {
        tree.right().accept(this, data);
        push("%rax");
        tree.left().accept(this, data);
        pop("%rcx");
        
        switch (tree.operator()) {
            case PLUS -> code.append("    addq %rcx, %rax\n");
            case MINUS -> {
                code.append("    subq %rcx, %rax\n");
                code.append("    negq %rax\n");
            }
            case MUL -> code.append("    imulq %rcx, %rax\n");
            case DIV -> {
                code.append("    cqto\n");
                code.append("    idivq %rcx\n");
            }
            case MOD -> {
                code.append("    cqto\n");
                code.append("    idivq %rcx\n");
                code.append("    movq %rdx, %rax\n");
            }
            case BIT_AND -> code.append("    andq %rcx, %rax\n");
            case BIT_OR -> code.append("    orq %rcx, %rax\n");
            case BIT_XOR -> code.append("    xorq %rcx, %rax\n");
            case SHIFT_LEFT -> {
                code.append("    movb %cl, %cl\n");
                code.append("    shlq %cl, %rax\n");
            }
            case SHIFT_RIGHT -> {
                code.append("    movb %cl, %cl\n");
                code.append("    sarq %cl, %rax\n");
            }
            case EQUAL -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    sete %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case NOT_EQUAL -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    setne %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case LESS -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    setl %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case LESS_EQUAL -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    setle %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case GREATER -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    setg %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case GREATER_EQUAL -> {
                code.append("    cmpq %rcx, %rax\n");
                code.append("    setge %al\n");
                code.append("    movzbq %al, %rax\n");
            }
            case LOGICAL_AND -> {
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
            case LOGICAL_OR -> {
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
            case MINUS -> code.append("    negq %rax\n");
            case BIT_NOT -> code.append("    notq %rax\n");
            case LOGICAL_NOT -> {
                code.append("    testq %rax, %rax\n");
                code.append("    setz %al\n");
                code.append("    movzbq %al, %rax\n");
            }
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
        String name = tree.name().name().identifier();
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
} 