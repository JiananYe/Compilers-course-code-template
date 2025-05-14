package edu.kit.kastel.vads.compiler.backend.aasm;

import edu.kit.kastel.vads.compiler.backend.regalloc.Register;
import edu.kit.kastel.vads.compiler.backend.regalloc.RegisterAllocator;
import edu.kit.kastel.vads.compiler.ir.IrGraph;
import edu.kit.kastel.vads.compiler.ir.node.Node;
import edu.kit.kastel.vads.compiler.ir.node.ProjNode;
import edu.kit.kastel.vads.compiler.ir.node.StartNode;
import edu.kit.kastel.vads.compiler.ir.node.Block;
import edu.kit.kastel.vads.compiler.ir.node.ReturnNode;
import edu.kit.kastel.vads.compiler.ir.node.ConstIntNode;
import edu.kit.kastel.vads.compiler.ir.node.DivNode;
import edu.kit.kastel.vads.compiler.ir.node.ModNode;
import edu.kit.kastel.vads.compiler.ir.node.BinaryOperationNode;
import edu.kit.kastel.vads.compiler.ir.util.NodeSupport;

import java.util.*;

public class GraphColoringRegisterAllocator implements RegisterAllocator {
    private static final int NUM_PHYSICAL_REGS = 3; // %rbx, %rcx, %rdx
    private final Map<Node, Set<Node>> interferenceGraph = new HashMap<>();
    private final Map<Node, Register> registers = new HashMap<>();
    private final Stack<Node> stack = new Stack<>();
    private final Set<Node> spillCandidates = new HashSet<>();
    private int nextConstReg = 0;

    @Override
    public Map<Node, Register> allocateRegisters(IrGraph graph) {
        buildInterferenceGraph(graph);
        simplify();
        select();
        return Map.copyOf(registers);
    }

    private void buildInterferenceGraph(IrGraph graph) {
        // Initialize interference graph
        Set<Node> visited = new HashSet<>();
        scan(graph.endBlock(), visited);

        // Build interference edges
        for (Node node : interferenceGraph.keySet()) {
            for (Node other : interferenceGraph.keySet()) {
                if (node != other && interferes(node, other)) {
                    interferenceGraph.get(node).add(other);
                    interferenceGraph.get(other).add(node);
                }
            }
        }
    }

    private void scan(Node node, Set<Node> visited) {
        if (!visited.add(node)) {
            return;
        }

        // Add node to interference graph if it needs a register
        if (needsRegister(node)) {
            interferenceGraph.put(node, new HashSet<>());
        }

        // Recursively process predecessors
        for (Node predecessor : node.predecessors()) {
            scan(predecessor, visited);
        }
    }

    private boolean interferes(Node a, Node b) {
        // Two nodes interfere if they are live at the same time
        // For division operations, we need special handling
        if (a instanceof DivNode || a instanceof ModNode || b instanceof DivNode || b instanceof ModNode) {
            // For division, we need to ensure the divisor is in %rcx and dividend in %rax
            if (a instanceof DivNode || a instanceof ModNode) {
                Node divisor = NodeSupport.predecessorSkipProj(a, BinaryOperationNode.RIGHT);
                if (divisor == b) {
                    return true; // Divisor must be in %rcx
                }
            }
            if (b instanceof DivNode || b instanceof ModNode) {
                Node divisor = NodeSupport.predecessorSkipProj(b, BinaryOperationNode.RIGHT);
                if (divisor == a) {
                    return true; // Divisor must be in %rcx
                }
            }
        }
        
        // For constants, they don't interfere with other nodes unless they're used in division
        if (a instanceof ConstIntNode || b instanceof ConstIntNode) {
            boolean aUsedInDiv = a.predecessors().stream().anyMatch(p -> p instanceof DivNode || p instanceof ModNode);
            boolean bUsedInDiv = b.predecessors().stream().anyMatch(p -> p instanceof DivNode || p instanceof ModNode);
            if (!aUsedInDiv && !bUsedInDiv) {
                return false;
            }
        }
        return a.predecessors().stream()
                .anyMatch(pred -> b.predecessors().contains(pred));
    }

    private void simplify() {
        boolean changed;
        do {
            changed = false;
            for (Node node : new HashSet<>(interferenceGraph.keySet())) {
                if (interferenceGraph.get(node).size() < NUM_PHYSICAL_REGS) {
                    stack.push(node);
                    interferenceGraph.remove(node);
                    // Remove this node from other nodes' interference sets
                    for (Set<Node> edges : interferenceGraph.values()) {
                        edges.remove(node);
                    }
                    changed = true;
                }
            }
        } while (changed);

        // If we still have nodes, they are potential spill candidates
        spillCandidates.addAll(interferenceGraph.keySet());
    }

    private void select() {
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            Set<Register> availableRegs = new HashSet<>(Arrays.asList(
                new VirtualRegister(0), // %rbx
                new VirtualRegister(1), // %rcx
                new VirtualRegister(2)  // %rdx
            ));

            // Remove registers used by interfering nodes
            for (Node interfering : interferenceGraph.getOrDefault(node, Collections.emptySet())) {
                Register reg = registers.get(interfering);
                if (reg != null) {
                    availableRegs.remove(reg);
                }
            }

            // Special handling for division operands
            if (node.predecessors().stream().anyMatch(p -> p instanceof DivNode || p instanceof ModNode)) {
                for (Node pred : node.predecessors()) {
                    if (pred instanceof DivNode || pred instanceof ModNode) {
                        Node divisor = NodeSupport.predecessorSkipProj(pred, BinaryOperationNode.RIGHT);
                        if (divisor == node) {
                            // This is the divisor, it must go in %rcx
                            registers.put(node, new VirtualRegister(1)); // %rcx
                            continue;
                        }
                    }
                }
            }

            // For constants, use a different register each time
            if (node instanceof ConstIntNode) {
                Register reg = new VirtualRegister(nextConstReg % NUM_PHYSICAL_REGS);
                nextConstReg++;
                registers.put(node, reg);
                continue;
            }

            if (!availableRegs.isEmpty()) {
                // Assign the first available register
                registers.put(node, availableRegs.iterator().next());
            } else {
                // No register available, must spill
                int stackOffset = (registers.size() - NUM_PHYSICAL_REGS) * 8;
                registers.put(node, new VirtualRegister(3 + stackOffset / 8));
            }
        }

        // Handle spill candidates
        for (Node node : spillCandidates) {
            int stackOffset = (registers.size() - NUM_PHYSICAL_REGS) * 8;
            registers.put(node, new VirtualRegister(3 + stackOffset / 8));
        }
    }

    private static boolean needsRegister(Node node) {
        return !(node instanceof ProjNode || 
                node instanceof StartNode || 
                node instanceof Block || 
                node instanceof ReturnNode);
    }
} 