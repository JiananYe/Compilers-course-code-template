package edu.kit.kastel.vads.compiler.backend.aasm;

import edu.kit.kastel.vads.compiler.backend.regalloc.Register;
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

public class CodeGenerator {

    public String generateCode(List<IrGraph> program) {
        StringBuilder builder = new StringBuilder();
        builder.append(".global main\n");
        builder.append(".global _main\n");
        builder.append(".text\n");
        builder.append("main:\n");
        builder.append("    call _main\n");
        builder.append("    movq %rax, %rdi\n");
        builder.append("    movq $0x3C, %rax\n");
        builder.append("    syscall\n");
        builder.append("_main:\n");
        
        for (IrGraph graph : program) {
            AasmRegisterAllocator allocator = new AasmRegisterAllocator();
            Map<Node, Register> registers = allocator.allocateRegisters(graph);
            generateForGraph(graph, builder, registers);
        }
        
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
                builder.append("    movq ").append(getRegisterName(left)).append(", %rax\n");
                builder.append("    addq ").append(getRegisterName(right)).append(", %rax\n");
                builder.append("    movq %rax, ").append(getRegisterName(result)).append("\n");
            }
            case SubNode sub -> {
                Register result = registers.get(sub);
                Register left = registers.get(predecessorSkipProj(sub, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(sub, BinaryOperationNode.RIGHT));
                builder.append("    movq ").append(getRegisterName(left)).append(", %rax\n");
                builder.append("    subq ").append(getRegisterName(right)).append(", %rax\n");
                builder.append("    movq %rax, ").append(getRegisterName(result)).append("\n");
            }
            case MulNode mul -> {
                Register result = registers.get(mul);
                Register left = registers.get(predecessorSkipProj(mul, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mul, BinaryOperationNode.RIGHT));
                builder.append("    movq ").append(getRegisterName(left)).append(", %rax\n");
                builder.append("    imulq ").append(getRegisterName(right)).append(", %rax\n");
                builder.append("    movq %rax, ").append(getRegisterName(result)).append("\n");
            }
            case DivNode div -> {
                Register result = registers.get(div);
                Register left = registers.get(predecessorSkipProj(div, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(div, BinaryOperationNode.RIGHT));
                builder.append("    movq ").append(getRegisterName(left)).append(", %rax\n");
                builder.append("    cqto\n");
                builder.append("    idivq ").append(getRegisterName(right)).append("\n");
                builder.append("    movq %rax, ").append(getRegisterName(result)).append("\n");
            }
            case ModNode mod -> {
                Register result = registers.get(mod);
                Register left = registers.get(predecessorSkipProj(mod, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mod, BinaryOperationNode.RIGHT));
                builder.append("    movq ").append(getRegisterName(left)).append(", %rax\n");
                builder.append("    cqto\n");
                builder.append("    idivq ").append(getRegisterName(right)).append("\n");
                builder.append("    movq %rdx, ").append(getRegisterName(result)).append("\n");
            }
            case ConstIntNode c -> {
                Register reg = registers.get(c);
                builder.append("    movq $").append(c.value()).append(", ").append(getRegisterName(reg)).append("\n");
            }
            case ReturnNode r -> {
                Register result = registers.get(predecessorSkipProj(r, ReturnNode.RESULT));
                builder.append("    movq ").append(getRegisterName(result)).append(", %rax\n");
                builder.append("    ret\n");
            }
            case Phi phi -> {
                throw new UnsupportedOperationException("phi");
            }
            case Block _, ProjNode _, StartNode _ -> {
                // do nothing
                return;
            }
            default -> throw new UnsupportedOperationException("Unsupported node type: " + node.getClass().getSimpleName());
        }
    }

    private String getRegisterName(Register reg) {
        // Convert abstract register names to x86-64 register names
        return switch (reg.toString()) {
            case "%0" -> "%rbx";
            case "%1" -> "%rcx";
            case "%2" -> "%rdx";
            default -> throw new IllegalArgumentException("Unsupported register: " + reg);
        };
    }
}
