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
        // Stack space will be allocated after register assignment
        int totalStack = 0;
        Map<Register, Integer> stackOffsets = new HashMap<>();
        int nextOffset = -4;
        for (IrGraph graph : program) {
            RegisterAllocator allocator = new GraphColoringRegisterAllocator();
            Map<Node, Register> registers = allocator.allocateRegisters(graph);
            // Assign stack slots for virtual registers
            for (Register reg : registers.values()) {
                if (isVirtualRegister(reg)) {
                    if (!stackOffsets.containsKey(reg)) {
                        stackOffsets.put(reg, nextOffset);
                        nextOffset -= 4;
                    }
                }
            }
            // Align stack to 16 bytes
            totalStack = ((-nextOffset + 15) / 16) * 16;
            builder.append("    subq $").append(totalStack).append(", %rsp\n");
            generateForGraph(graph, builder, registers, stackOffsets);
        }
        builder.append("    movl %ebx, %eax\n"); 
        builder.append("    popq %rdx\n");            // Restore callee-saved registers
        builder.append("    popq %rcx\n");
        builder.append("    popq %rbx\n");
        builder.append("    movq %rbp, %rsp\n");      // Restore stack pointer
        builder.append("    popq %rbp\n");            // Restore base pointer
        builder.append("    ret\n");                   // Return with result in %rax
        return builder.toString();
    }

    private boolean isVirtualRegister(Register reg) {
        String regName = reg.toString();
        if (regName.startsWith("%")) {
            int regNum = Integer.parseInt(regName.substring(1));
            return regNum >= 3;
        }
        return false;
    }

    private void generateForGraph(IrGraph graph, StringBuilder builder, Map<Node, Register> registers, Map<Register, Integer> stackOffsets) {
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
        // Use IrGraph.getAllNodes() to collect all nodes
        Set<Node> allNodes = graph.getAllNodes();
        for (Node n : allNodes) {
            if (n instanceof Block b) continue;
            Block blk = n.block();
            blockNodes.computeIfAbsent(blk, k -> new ArrayList<>()).add(n);
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
            List<Block> successors = new ArrayList<>();
            for (Node succ : graph.successors(block)) {
                if (succ instanceof Block sb) successors.add(sb);
            }
            // Emit code for all nodes in the block (except Phi)
            List<Node> nodes = blockNodes.getOrDefault(block, List.of());
            for (Node node : nodes) {
                if (!(node instanceof Phi)) {
                    // Only emit if node belongs to this block and hasn't been emitted globally
                    Register reg = registers.get(node);
                    if (reg == null) {
                        // Skip nodes that do not need a register (e.g., constants used only as operands)
                        continue;
                    }
                    if (node.block() == block && globalVisited.add(node)) {
                        scan(node, globalVisited, builder, registers, stackOffsets);
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
                                    builder.append("    movl ").append(getRegisterName(src, stackOffsets)).append(", ").append(getRegisterName(dest, stackOffsets)).append("\n");
                                }
                            }
                        }
                    }
                }
            }
            // Emit control flow: if this block ends with a conditional, emit jump logic
            if (successors.size() == 2 && !block.equals(graph.endBlock())) {
                // Heuristic: if two successors, treat as conditional (then/else)
                if (!nodes.isEmpty()) {
                    Node condNode = null;
                    for (int i = nodes.size() - 1; i >= 0; --i) {
                        Node candidate = nodes.get(i);
                        if (!(candidate instanceof Phi) && registers.get(candidate) != null) {
                            condNode = candidate;
                            break;
                        }
                    }
                    if (condNode == null) {
                        throw new IllegalStateException("No non-Phi node with register found for conditional jump");
                    }
                    Register condReg = registers.get(condNode);
                    // If the condition node is a LessNode or EqualNode, its result is in %al after setl/sete
                    // Emit testb %al, %al and conditional jump
                    String condRegName = getRegisterName(condReg, stackOffsets);
                    if (condNode.getClass().getSimpleName().equals("LessNode") || condNode.getClass().getSimpleName().equals("EqualNode")) {
                        builder.append("    testb %al, %al\n");
                        builder.append("    jne ").append(blockLabels.get(successors.get(0))).append("\n");
                        builder.append("    jmp ").append(blockLabels.get(successors.get(1))).append("\n");
                    } else {
                        builder.append("    cmpl $0, ").append(condRegName).append("\n");
                        builder.append("    jne ").append(blockLabels.get(successors.get(0))).append("\n");
                        builder.append("    jmp ").append(blockLabels.get(successors.get(1))).append("\n");
                    }
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

    private void scan(Node node, Set<Node> visited, StringBuilder builder, Map<Node, Register> registers, Map<Register, Integer> stackOffsets) {
        // Skip nodes that do not need a register (e.g., constants used only as operands)
        Register reg = registers.get(node);
        if (reg == null) {
            return;
        }
        // For leaf nodes, do not recursively scan predecessors
        if (node instanceof edu.kit.kastel.vads.compiler.ir.node.ConstIntNode ||
            node.getClass().getSimpleName().equals("LessNode")) {
            // Emit code for this node only
            switch (node) {
                case edu.kit.kastel.vads.compiler.ir.node.ConstIntNode c -> {
                    builder.append("    movl $").append(c.value()).append(", ").append(getRegisterName(reg, stackOffsets)).append("\n");
                }
                case edu.kit.kastel.vads.compiler.ir.node.LessNode less -> {
                    Register result = registers.get(less);
                    Register left = registers.get(less.predecessor(0));
                    Register right = registers.get(less.predecessor(1));
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", %eax\n");
                    builder.append("    cmpl ").append(getRegisterName(right, stackOffsets)).append(", %eax\n");
                    builder.append("    setl %al\n");
                    // Only emit movzbl if the result is used as a value elsewhere (not for conditional jump)
                    // This can be handled by generateForGraph when needed
                }
                default -> {}
            }
            return;
        }
        for (Node predecessor : node.predecessors()) {
            if (visited.add(predecessor)) {
                scan(predecessor, visited, builder, registers, stackOffsets);
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
                        builder.append("    addl $").append(c.value()).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    } else {
                        builder.append("    addl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    }
                } else {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    if (rightNode instanceof ConstIntNode c) {
                        builder.append("    addl $").append(c.value()).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    } else {
                        builder.append("    addl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
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
                    builder.append("    movl ").append(getRegisterName(right, stackOffsets)).append(", %ecx\n");
                    builder.append("    negl %ecx\n");
                    builder.append("    movl %ecx, ").append(getRegisterName(result, stackOffsets)).append("\n");
                } else {
                    if (!result.equals(left)) {
                        builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    }
                    builder.append("    subl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
            }
            case MulNode mul -> {
                Register result = registers.get(mul);
                Register left = registers.get(predecessorSkipProj(mul, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mul, BinaryOperationNode.RIGHT));
                if (result.equals(left)) {
                    builder.append("    imull ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                } else if (result.equals(right)) {
                    builder.append("    imull ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                } else {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                    builder.append("    imull ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
            }
            case DivNode div -> {
                Register result = registers.get(div);
                Register left = registers.get(predecessorSkipProj(div, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(div, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", %eax\n");
                builder.append("    movl ").append(getRegisterName(right, stackOffsets)).append(", %ecx\n");
                builder.append("    cltd\n");
                builder.append("    idivl %ecx\n");
                builder.append("    movl %eax, ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case ModNode mod -> {
                Register result = registers.get(mod);
                Register left = registers.get(predecessorSkipProj(mod, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(mod, BinaryOperationNode.RIGHT));
                builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", %eax\n");
                builder.append("    movl ").append(getRegisterName(right, stackOffsets)).append(", %ecx\n");
                builder.append("    cltd\n");
                builder.append("    idivl %ecx\n");
                builder.append("    movl %edx, ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case ReturnNode r -> {
                Register result = registers.get(predecessorSkipProj(r, ReturnNode.RESULT));
                if (result != null) {
                    builder.append("    movl ").append(getRegisterName(result, stackOffsets)).append(", %eax\n");
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
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                // x86 expects shift amount in %cl
                if (!getRegisterName(right, stackOffsets).equals("%ecx")) {
                    builder.append("    movl ").append(getRegisterName(right, stackOffsets)).append(", %ecx\n");
                }
                builder.append("    shll %cl, ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case ShrNode shr -> {
                Register result = registers.get(shr);
                Register left = registers.get(predecessorSkipProj(shr, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(shr, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                // x86 expects shift amount in %cl
                if (!getRegisterName(right, stackOffsets).equals("%ecx")) {
                    builder.append("    movl ").append(getRegisterName(right, stackOffsets)).append(", %ecx\n");
                }
                builder.append("    sarl %cl, ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case OrNode or -> {
                Register result = registers.get(or);
                Register left = registers.get(predecessorSkipProj(or, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(or, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                builder.append("    orl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case NotNode not -> {
                Register result = registers.get(not);
                Register operand = registers.get(not.predecessors().get(0));
                if (!result.equals(operand)) {
                    builder.append("    movl ").append(getRegisterName(operand, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                builder.append("    notl ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case EqualNode eq -> {
                Register result = registers.get(eq);
                Register left = registers.get(eq.predecessor(0));
                Register right = registers.get(eq.predecessor(1));
                builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", %eax\n");
                builder.append("    cmpl ").append(getRegisterName(right, stackOffsets)).append(", %eax\n");
                builder.append("    sete %al\n");
                // Only emit movzbl if the result is used as a value elsewhere (not for conditional jump)
                // This can be handled by generateForGraph when needed
            }
            case AndNode and -> {
                Register result = registers.get(and);
                Register left = registers.get(predecessorSkipProj(and, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(and, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                builder.append("    andl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            case XorNode xor -> {
                Register result = registers.get(xor);
                Register left = registers.get(predecessorSkipProj(xor, BinaryOperationNode.LEFT));
                Register right = registers.get(predecessorSkipProj(xor, BinaryOperationNode.RIGHT));
                if (!result.equals(left)) {
                    builder.append("    movl ").append(getRegisterName(left, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
                }
                builder.append("    xorl ").append(getRegisterName(right, stackOffsets)).append(", ").append(getRegisterName(result, stackOffsets)).append("\n");
            }
            default -> throw new UnsupportedOperationException("Unsupported node type: " + node.getClass().getSimpleName());
        }
    }

    private String getRegisterName(Register reg, Map<Register, Integer> stackOffsets) {
        String regName = reg.toString();
        if (regName.startsWith("%")) {
            int regNum = Integer.parseInt(regName.substring(1));
            if (regNum == 0) return "%ebx";
            if (regNum == 1) return "%ecx";
            if (regNum == 2) return "%edx";
            // For virtual registers, use stack slot
            if (regNum >= 3) {
                return stackOffsets.get(reg) + "(%rbp)";
            }
        }
        throw new IllegalArgumentException("Invalid register format: " + reg);
    }
}

