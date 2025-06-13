package edu.kit.kastel.vads.compiler.codegen;

import edu.kit.kastel.vads.compiler.backend.aasm.GraphColoringRegisterAllocator;
import edu.kit.kastel.vads.compiler.backend.regalloc.Register;
import edu.kit.kastel.vads.compiler.backend.regalloc.RegisterAllocator;
import edu.kit.kastel.vads.compiler.ir.IrGraph;
import edu.kit.kastel.vads.compiler.ir.node.AddNode;
import edu.kit.kastel.vads.compiler.ir.node.BinaryOperationNode;
import edu.kit.kastel.vads.compiler.ir.node.Block;
import edu.kit.kastel.vads.compiler.ir.node.ConstIntNode;
import edu.kit.kastel.vads.compiler.ir.node.DivNode;
import edu.kit.kastel.vads.compiler.ir.node.ModNode;
import edu.kit.kastel.vads.compiler.ir.node.MulNode;
import edu.kit.kastel.vads.compiler.ir.node.Node;
import edu.kit.kastel.vads.compiler.ir.node.Phi;
import edu.kit.kastel.vads.compiler.ir.node.ProjNode;
import edu.kit.kastel.vads.compiler.ir.node.ReturnNode;
import edu.kit.kastel.vads.compiler.ir.node.StartNode;
import edu.kit.kastel.vads.compiler.ir.node.SubNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static edu.kit.kastel.vads.compiler.ir.util.NodeSupport.predecessorSkipProj;

public class CodeGeneratorL2 {

    public String generateCode(List<IrGraph> program) {
        StringBuilder builder = new StringBuilder();
        builder.append("    .section .text\n");
        builder.append("    .global main\n");
        builder.append("    .global _main\n");
        builder.append("main:\n");
        builder.append("    call _main\n");
        builder.append("    movq %rax, %rdi\n");
        builder.append("    movq $0x3C, %rax\n");
        builder.append("    syscall\n");
        builder.append("_main:\n");
        builder.append("    pushq %rbp\n");
        builder.append("    movq %rsp, %rbp\n");
        builder.append("    subq $1024, %rsp\n");
        builder.append("    pushq %rbx\n");
        builder.append("    pushq %rcx\n");
        builder.append("    pushq %rdx\n");
        
        for (IrGraph graph : program) {
            RegisterAllocator allocator = new GraphColoringRegisterAllocator();
            Map<Node, Register> registers = allocator.allocateRegisters(graph);
            generateForGraph(graph, builder, registers);
        }
        
        builder.append("    popq %rdx\n");            // Restore callee-saved registers
        builder.append("    popq %rcx\n");
        builder.append("    popq %rbx\n");
        builder.append("    movq %rbp, %rsp\n");      // Restore stack pointer
        builder.append("    popq %rbp\n");            // Restore base pointer
        builder.append("    ret\n");                   // Return with result in %rax
        
        return builder.toString();
    }

    private void generateForGraph(IrGraph graph, StringBuilder builder, Map<Node, Register> registers) {
        Set<Node> visited = new HashSet<>();
        scan(graph.endBlock(), visited, builder, registers);
    }

    private void scan(Node node, Set<Node> visited, StringBuilder builder, Map<Node, Register> registers) {
        for (Node predecessor : node.predecessors()) {
            if (visited.add(predecessor)) {
                scan(predecessor, visited, builder, registers);
            }
        }

        switch (node) {
            case AddNode add -> {
                Register result = registers.get(add);
                Register left = registers.get(predecessorSkipProj(add, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(add, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    addl ").append(getRegisterName(right)).append(", %eax\n");
                builder.append("    movl %eax, ").append(getRegisterName(result)).append("\n");
            }
            case SubNode sub -> {
                Register result = registers.get(sub);
                Register left = registers.get(predecessorSkipProj(sub, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(sub, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    subl ").append(getRegisterName(right)).append(", %eax\n");
                builder.append("    movl %eax, ").append(getRegisterName(result)).append("\n");
            }
            case MulNode mul -> {
                Register result = registers.get(mul);
                Register left = registers.get(predecessorSkipProj(mul, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mul, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    imull ").append(getRegisterName(right)).append(", %eax\n");
                builder.append("    movl %eax, ").append(getRegisterName(result)).append("\n");
            }
            case DivNode div -> {
                Register result = registers.get(div);
                Register left = registers.get(predecessorSkipProj(div, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(div, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    movl ").append(getRegisterName(right)).append(", %ecx\n");
                builder.append("    cltd\n");
                builder.append("    idivl %ecx\n");
                builder.append("    movl %eax, ").append(getRegisterName(result)).append("\n");
            }
            case ModNode mod -> {
                Register result = registers.get(mod);
                Register left = registers.get(predecessorSkipProj(mod, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mod, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    movl ").append(getRegisterName(right)).append(", %ecx\n");
                builder.append("    cltd\n");
                builder.append("    idivl %ecx\n");
                builder.append("    movl %edx, ").append(getRegisterName(result)).append("\n");
            }
            case ConstIntNode c -> {
                Register reg = registers.get(c);
                builder.append("    movl $").append(c.value()).append(", ").append(getRegisterName(reg)).append("\n");
            }
            case ReturnNode r -> {
                Register result = registers.get(predecessorSkipProj(r, ReturnNode.RESULT));
                if (result != null) {
                    builder.append("    movl ").append(getRegisterName(result)).append(", %eax\n");
                }
            }
            case Phi _, Block _, ProjNode _, StartNode _ -> {
                // do nothing
                return;
            }
            default -> throw new UnsupportedOperationException("Unsupported node type: " + node.getClass().getSimpleName());
        }
    }

    private String getRegisterName(Register reg) {
        // Convert abstract register names to x86-64 32-bit register names
        String regName = reg.toString();
        if (regName.startsWith("%")) {
            int regNum = Integer.parseInt(regName.substring(1));
            if (regNum < 3) {
                return switch (regNum) {
                    case 0 -> "%ebx";
                    case 1 -> "%ecx";
                    case 2 -> "%edx";
                    default -> throw new IllegalArgumentException("Unsupported register: " + reg);
                };
            } else {
                // For registers beyond %2, use stack locations
                // Each stack slot is 4 bytes (32 bits)
                int stackOffset = (regNum - 3) * 4;
                return stackOffset + "(%rsp)";
            }
        }
        throw new IllegalArgumentException("Invalid register format: " + reg);
    }
}
