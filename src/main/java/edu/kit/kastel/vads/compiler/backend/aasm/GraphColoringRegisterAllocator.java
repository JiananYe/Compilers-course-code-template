package edu.kit.kastel.vads.compiler.backend.aasm;

import edu.kit.kastel.vads.compiler.backend.regalloc.Register;
import edu.kit.kastel.vads.compiler.backend.regalloc.RegisterAllocator;
import edu.kit.kastel.vads.compiler.ir.IrGraph;
import edu.kit.kastel.vads.compiler.ir.node.*;
import edu.kit.kastel.vads.compiler.ir.util.NodeSupport;

import java.util.*;

public class GraphColoringRegisterAllocator implements RegisterAllocator {
    private static final int NUM_PHYSICAL_REGS = 3; // %rbx, %rcx, %rdx
    private final Map<Node, Set<Node>> interferenceGraph = new HashMap<>();
    private final Map<Node, Register> registers = new HashMap<>();
    private final Stack<Node> stack = new Stack<>();
    private final Set<Node> spillCandidates = new HashSet<>();
    private final Map<Node, Set<Node>> moveRelated = new HashMap<>();
    private int nextConstReg = 0;

    @Override
    public Map<Node, Register> allocateRegisters(IrGraph graph) {
        buildInterferenceGraph(graph);
        buildMoveRelated(graph);
        maximumCardinalitySearch();
        coalesce();
        simplify();
        select();
        return Map.copyOf(registers);
    }

    private void buildMoveRelated(IrGraph graph) {
        // Find nodes that are related by moves (same value)
        Set<Node> visited = new HashSet<>();
        scanForMoves(graph.endBlock(), visited);
    }

    private void scanForMoves(Node node, Set<Node> visited) {
        if (!visited.add(node)) {
            return;
        }

        // Check if this node is a move (same value as its predecessor)
        if (node instanceof ProjNode) {
            Node source = node.predecessor(ProjNode.IN);
            if (source instanceof ConstIntNode) {
                moveRelated.computeIfAbsent(source, k -> new HashSet<>()).add(node);
                moveRelated.computeIfAbsent(node, k -> new HashSet<>()).add(source);
            }
        }

        for (Node predecessor : node.predecessors()) {
            scanForMoves(predecessor, visited);
        }
    }

    private void maximumCardinalitySearch() {
        // MCS algorithm for better node ordering
        Set<Node> unprocessed = new HashSet<>(interferenceGraph.keySet());
        Map<Node, Integer> weights = new HashMap<>();
        
        while (!unprocessed.isEmpty()) {
            // Find node with maximum weight
            Node maxNode = null;
            int maxWeight = -1;
            for (Node node : unprocessed) {
                int weight = weights.getOrDefault(node, 0);
                if (weight > maxWeight) {
                    maxWeight = weight;
                    maxNode = node;
                }
            }
            
            // Add to stack and update weights
            stack.push(maxNode);
            unprocessed.remove(maxNode);
            
            // Update weights of neighbors
            for (Node neighbor : interferenceGraph.get(maxNode)) {
                if (unprocessed.contains(neighbor)) {
                    weights.merge(neighbor, 1, Integer::sum);
                }
            }
        }
    }

    private void coalesce() {
        // Coalesce non-interfering move-related nodes
        boolean changed;
        do {
            changed = false;
            for (Node node : new HashSet<>(interferenceGraph.keySet())) {
                Set<Node> related = moveRelated.getOrDefault(node, Collections.emptySet());
                for (Node relatedNode : related) {
                    if (!interferes(node, relatedNode)) {
                        // Coalesce the nodes
                        interferenceGraph.get(node).addAll(interferenceGraph.get(relatedNode));
                        interferenceGraph.remove(relatedNode);
                        changed = true;
                    }
                }
            }
        } while (changed);
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