package edu.kit.kastel.vads.compiler.backend.aasm;

import edu.kit.kastel.vads.compiler.backend.regalloc.Register;
import edu.kit.kastel.vads.compiler.backend.regalloc.RegisterAllocator;
import edu.kit.kastel.vads.compiler.ir.IrGraph;
import edu.kit.kastel.vads.compiler.ir.node.*;
import edu.kit.kastel.vads.compiler.ir.util.NodeSupport;
import edu.kit.kastel.vads.compiler.ir.optimize.LivenessAnalyzer;

import java.util.*;

public class GraphColoringRegisterAllocator implements RegisterAllocator {
    private static final int NUM_PHYSICAL_REGS = 6; // TEMP: allow more unique registers for debugging
    private final Map<Node, Set<Node>> interferenceGraph = new HashMap<>();
    private final Map<Node, Register> registers = new HashMap<>();
    private final Stack<Node> stack = new Stack<>();
    private final Set<Node> spillCandidates = new HashSet<>();
    private final Map<Node, Set<Node>> moveRelated = new HashMap<>();
    private int nextConstReg = 0;
    private Map<Node, Set<Node>> fullInterferenceGraph;

    @Override
    public Map<Node, Register> allocateRegisters(IrGraph graph) {
        buildInterferenceGraph(graph);
        // Make a deep copy of the interference graph for use during register assignment
        fullInterferenceGraph = new HashMap<>();
        for (Map.Entry<Node, Set<Node>> entry : interferenceGraph.entrySet()) {
            fullInterferenceGraph.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
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
        // Use liveness analysis to build the interference graph
        LivenessAnalyzer liveness = new LivenessAnalyzer(graph);
        liveness.analyze();
        Set<Node> allNodes = new HashSet<>();
        collectAllNodes(graph.endBlock(), allNodes);
        Set<Node> visited = new HashSet<>();
        scan(graph.endBlock(), visited);
        allNodes.addAll(interferenceGraph.keySet());

        // For each node, add interference with all nodes live-out at that node
        for (Node node : allNodes) {
            Set<Node> liveOut = liveness.getLiveOut(node);
            for (Node other : liveOut) {
                if (other != node && needsRegister(other) && needsRegister(node)) {
                    interferenceGraph.get(node).add(other);
                    if (interferenceGraph.containsKey(other)) {
                        interferenceGraph.get(other).add(node);
                    }
                }
            }
        }
        // Ensure both operands of each binary operation interfere
        for (Node node : allNodes) {
            if (node instanceof BinaryOperationNode) {
                Node left = NodeSupport.predecessorSkipProj(node, BinaryOperationNode.LEFT);
                Node right = NodeSupport.predecessorSkipProj(node, BinaryOperationNode.RIGHT);
                if (needsRegister(left) && needsRegister(right)) {
                    interferenceGraph.get(left).add(right);
                    interferenceGraph.get(right).add(left);
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
        List<Node> assignmentOrder = new ArrayList<>();
        while (!stack.isEmpty()) {
            assignmentOrder.add(stack.pop());
        }
        Collections.reverse(assignmentOrder);
        for (Node node : assignmentOrder) {
            Set<Register> availableRegs = new LinkedHashSet<>(Arrays.asList(
                new VirtualRegister(0), // %rbx
                new VirtualRegister(1), // %rcx
                new VirtualRegister(2), // %rdx
                new VirtualRegister(3), // %rbx
                new VirtualRegister(4), // %rcx
                new VirtualRegister(5)  // %rdx
            ));

            // Remove registers used by interfering nodes
            for (Node interfering : fullInterferenceGraph.getOrDefault(node, Collections.emptySet())) {
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
                            registers.put(node, new VirtualRegister(1)); // %rcx
                            continue;
                        }
                    }
                }
            }

            // For constants, use a different register each time
            if (node instanceof ConstIntNode) {
                for (Node interfering : fullInterferenceGraph.getOrDefault(node, Collections.emptySet())) {
                    Register reg = registers.get(interfering);
                    if (reg != null) {
                        availableRegs.remove(reg);
                    }
                }
                Register reg;
                if (!availableRegs.isEmpty()) {
                    reg = availableRegs.iterator().next();
                } else {
                    int stackOffset = (registers.size() - NUM_PHYSICAL_REGS) * 8;
                    reg = new VirtualRegister(3 + stackOffset / 8);
                }
                registers.put(node, reg);
                continue;
            }

            if (!availableRegs.isEmpty()) {
                Register chosen = availableRegs.iterator().next();
                registers.put(node, chosen);
            } else {
                int stackOffset = (registers.size() - NUM_PHYSICAL_REGS) * 8;
                Register chosen = new VirtualRegister(3 + stackOffset / 8);
                registers.put(node, chosen);
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

    // Add this method to collect all nodes reachable from a given node
    private void collectAllNodes(Node node, Set<Node> visited) {
        if (!visited.add(node)) return;
        for (Node pred : node.predecessors()) {
            collectAllNodes(pred, visited);
        }
    }
} 