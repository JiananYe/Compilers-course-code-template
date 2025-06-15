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
import edu.kit.kastel.vads.compiler.ir.node.ShlNode;
import edu.kit.kastel.vads.compiler.ir.node.ShrNode;
import edu.kit.kastel.vads.compiler.ir.node.OrNode;
import edu.kit.kastel.vads.compiler.ir.node.NotNode;
import edu.kit.kastel.vads.compiler.ir.node.EqualNode;
import edu.kit.kastel.vads.compiler.ir.node.AndNode;
import edu.kit.kastel.vads.compiler.ir.node.XorNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;

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
        // 1. Collect all blocks by traversing the graph
        Set<Block> allBlocks = new HashSet<>();
        Deque<Block> blockWorklist = new ArrayDeque<>();
        blockWorklist.add(graph.startBlock());
        while (!blockWorklist.isEmpty()) {
            Block b = blockWorklist.pop();
            if (allBlocks.add(b)) {
                // For each successor of the block, if it's a block, add to worklist
                for (Node node : b.predecessors()) {
                    if (node instanceof Block predBlock) {
                        blockWorklist.add(predBlock);
                    }
                }
                // Also add successors
                for (Node node : graph.successors(b)) {
                    if (node instanceof Block succBlock) {
                        blockWorklist.add(succBlock);
                    }
                }
            }
        }
        // 2. Assign labels to blocks
        Map<Block, String> blockLabels = new HashMap<>();
        int blockId = 0;
        for (Block b : allBlocks) {
            blockLabels.put(b, "block_" + blockId++);
        }
        // 3. For each block, collect its nodes
        Map<Block, List<Node>> blockNodes = new HashMap<>();
        for (Block b : allBlocks) {
            blockNodes.put(b, new ArrayList<>());
        }
        // Traverse all nodes and assign to their block
        Set<Node> allNodes = new HashSet<>();
        collectAllNodes(graph.startBlock(), allNodes);
        Set<Node> allNodesFromEnd = new HashSet<>();
        collectAllNodes(graph.endBlock(), allNodesFromEnd);
        allNodes.addAll(allNodesFromEnd);
        for (Node n : allNodes) {
            if (n instanceof Block b) continue;
            Block blk = n.block();
            blockNodes.computeIfAbsent(blk, k -> new ArrayList<>());
            blockNodes.get(blk).add(n);
        }
        // 4. Emit code for blocks in a worklist respecting control flow
        Set<Block> emitted = new HashSet<>();
        Deque<Block> emitWorklist = new ArrayDeque<>();
        emitWorklist.add(graph.startBlock());
        Set<Node> globalVisited = new HashSet<>();
        while (!emitWorklist.isEmpty()) {
            Block block = emitWorklist.pop();
            if (!emitted.add(block)) continue;
            builder.append(blockLabels.get(block)).append(":\n");
            // Emit code for all nodes in the block (except Phi)
            List<Node> nodes = blockNodes.getOrDefault(block, List.of());
            for (Node node : nodes) {
                if (!(node instanceof Phi)) {
                    // Only emit if node belongs to this block and hasn't been emitted globally
                    if (node.block() == block && globalVisited.add(node)) {
                        scan(node, globalVisited, builder, registers);
                    }
                }
            }
            // For each successor, emit phi moves for this block
            for (Node succ : graph.successors(block)) {
                if (succ instanceof Block succBlock) {
                    for (Node node : blockNodes.getOrDefault(succBlock, List.of())) {
                        if (node instanceof Phi phi) {
                            int predIdx = succBlock.predecessors().indexOf(block);
                            if (predIdx >= 0 && predIdx < phi.predecessors().size()) {
                                Node incoming = phi.predecessors().get(predIdx);
                                Register src = registers.get(incoming);
                                Register dest = registers.get(phi);
                                if (!dest.equals(src)) {
                                    builder.append("    movl ").append(getRegisterName(src)).append(", ").append(getRegisterName(dest)).append("\n");
                                }
                            }
                        }
                    }
                }
            }
            // Emit control flow: if this block ends with a conditional, emit jump logic
            List<Block> successors = new ArrayList<>();
            for (Node succ : graph.successors(block)) {
                if (succ instanceof Block sb) successors.add(sb);
            }
            if (successors.size() == 2 && !block.equals(graph.endBlock())) {
                // Heuristic: if two successors, treat as conditional (then/else)
                // Assume last node in block computes the condition (should be an Equal/Greater/etc.)
                if (!nodes.isEmpty()) {
                    Node condNode = nodes.get(nodes.size() - 1);
                    Register condReg = registers.get(condNode);
                    builder.append("    cmpl $0, ").append(getRegisterName(condReg)).append("\n");
                    builder.append("    jne ").append(blockLabels.get(successors.get(0))).append("\n");
                    builder.append("    jmp ").append(blockLabels.get(successors.get(1))).append("\n");
                }
            } else if (successors.size() == 1 && !block.equals(graph.endBlock())) {
                builder.append("    jmp ").append(blockLabels.get(successors.get(0))).append("\n");
            }
            // Add successors to worklist
            for (Block sb : successors) {
                emitWorklist.add(sb);
            }
        }
    }

    private void collectAllNodes(Node node, Set<Node> visited) {
        if (!visited.add(node)) return;
        for (Node pred : node.predecessors()) {
            collectAllNodes(pred, visited);
        }
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
                Node rightNode = predecessorSkipProj(add, BinaryOperationNode.RIGHT);
                if (result.equals(left)) {
                    if (rightNode instanceof ConstIntNode c) {
                        builder.append("    addl $").append(c.value()).append(", ").append(getRegisterName(result)).append("\n");
                    } else {
                        builder.append("    addl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
                    }
                } else {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                    if (rightNode instanceof ConstIntNode c) {
                        builder.append("    addl $").append(c.value()).append(", ").append(getRegisterName(result)).append("\n");
                    } else {
                        builder.append("    addl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
                    }
                }
            }
            case SubNode sub -> {
                Register result = registers.get(sub);
                Node leftNode = predecessorSkipProj(sub, BinaryOperationNode.LEFT);
                Node rightNode = predecessorSkipProj(sub, BinaryOperationNode.RIGHT);
                Register left = registers.get(leftNode);
                Register right = registers.get(rightNode);
                if (leftNode instanceof ConstIntNode c && c.value() == 0) {
                    // Negation pattern
                    builder.append("    movl ").append(getRegisterName(right)).append(", %ecx\n");
                    builder.append("    negl %ecx\n");
                    builder.append("    movl %ecx, ").append(getRegisterName(result)).append("\n");
                } else {
                    if (!result.equals(left)) {
                        builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                    }
                    builder.append("    subl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
                }
            }
            case MulNode mul -> {
                Register result = registers.get(mul);
                Register left = registers.get(predecessorSkipProj(mul, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mul, BinaryOperationNode.RIGHT));
                if (result.equals(left)) {
                    builder.append("    imull ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
                } else if (result.equals(right)) {
                    builder.append("    imull ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                } else {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                    builder.append("    imull ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
                }
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
            case Phi phi -> {
                // Do nothing here; phi moves are handled at the end of predecessor blocks
            }
            case Block _, ProjNode _, StartNode _ -> {
                // do nothing
                return;
            }
            case ShlNode shl -> {
                Register result = registers.get(shl);
                Register left = registers.get(predecessorSkipProj(shl, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(shl, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                }
                // x86 expects shift amount in %cl
                if (!getRegisterName(right).equals("%ecx")) {
                    builder.append("    movl ").append(getRegisterName(right)).append(", %ecx\n");
                }
                builder.append("    shll %cl, ").append(getRegisterName(result)).append("\n");
            }
            case ShrNode shr -> {
                Register result = registers.get(shr);
                Register left = registers.get(predecessorSkipProj(shr, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(shr, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                }
                // x86 expects shift amount in %cl
                if (!getRegisterName(right).equals("%ecx")) {
                    builder.append("    movl ").append(getRegisterName(right)).append(", %ecx\n");
                }
                builder.append("    sarl %cl, ").append(getRegisterName(result)).append("\n");
            }
            case OrNode or -> {
                Register result = registers.get(or);
                Register left = registers.get(predecessorSkipProj(or, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(or, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                }
                builder.append("    orl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
            }
            case NotNode not -> {
                Register result = registers.get(not);
                Register operand = registers.get(not.predecessors().get(0));
                if (!result.equals(operand)) {
                    builder.append("    movl ").append(getRegisterName(operand)).append(", ").append(getRegisterName(result)).append("\n");
                }
                builder.append("    notl ").append(getRegisterName(result)).append("\n");
            }
            case EqualNode eq -> {
                Register result = registers.get(eq);
                Register left = registers.get(predecessorSkipProj(eq, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(eq, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left)).append(", %eax\n");
                builder.append("    cmpl ").append(getRegisterName(right)).append(", %eax\n");
                builder.append("    sete %al\n");
                builder.append("    movzbl %al, ").append(getRegisterName(result)).append("\n");
            }
            case AndNode and -> {
                Register result = registers.get(and);
                Register left = registers.get(predecessorSkipProj(and, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(and, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                }
                builder.append("    andl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
            }
            case XorNode xor -> {
                Register result = registers.get(xor);
                Register left = registers.get(predecessorSkipProj(xor, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(xor, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left)).append(", ").append(getRegisterName(result)).append("\n");
                }
                builder.append("    xorl ").append(getRegisterName(right)).append(", ").append(getRegisterName(result)).append("\n");
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
