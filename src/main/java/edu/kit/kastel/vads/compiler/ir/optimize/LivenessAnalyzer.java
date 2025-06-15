package edu.kit.kastel.vads.compiler.ir.optimize;

import edu.kit.kastel.vads.compiler.ir.IrGraph;
import edu.kit.kastel.vads.compiler.ir.node.*;

import java.util.*;

/**
 * Liveness analysis for IR nodes (SSA values).
 * Each Node is treated as a value/variable.
 *
 * Usage:
 *   LivenessAnalyzer analyzer = new LivenessAnalyzer(graph);
 *   analyzer.analyze();
 *   Set<Node> liveIn = analyzer.getLiveIn(node);
 *   Set<Node> liveOut = analyzer.getLiveOut(node);
 */
public class LivenessAnalyzer {
    private final IrGraph graph;
    private final Map<Node, Set<Node>> liveIn = new HashMap<>();
    private final Map<Node, Set<Node>> liveOut = new HashMap<>();

    public LivenessAnalyzer(IrGraph graph) {
        this.graph = graph;
    }

    public void analyze() {
        Set<Node> allNodes = collectAllNodes();
        for (Node node : allNodes) {
            liveIn.put(node, new HashSet<>());
            liveOut.put(node, new HashSet<>());
        }
        boolean changed;
        do {
            changed = false;
            for (Node node : allNodes) {
                Set<Node> oldIn = new HashSet<>(liveIn.get(node));
                Set<Node> oldOut = new HashSet<>(liveOut.get(node));

                // liveOut[n] = union of liveIn[s] for all successors s
                Set<Node> out = new HashSet<>();
                for (Node succ : graph.successors(node)) {
                    out.addAll(liveIn.get(succ));
                }
                liveOut.put(node, out);

                // use[n] = node.predecessors() (except for Block, ConstIntNode, StartNode)
                Set<Node> use = computeUse(node);
                // def[n] = node itself (except for Block, ConstIntNode, StartNode)
                Set<Node> def = computeDef(node);

                // liveIn[n] = use[n] ∪ (liveOut[n] - def[n])
                Set<Node> in = new HashSet<>(use);
                for (Node n : out) {
                    if (!def.contains(n)) {
                        in.add(n);
                    }
                }
                liveIn.put(node, in);

                if (!oldIn.equals(in) || !oldOut.equals(out)) {
                    changed = true;
                }
            }
        } while (changed);
    }

    private Set<Node> collectAllNodes() {
        // Traverse from startBlock and collect all reachable nodes (DFS)
        Set<Node> visited = new HashSet<>();
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(graph.startBlock());
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (visited.add(node)) {
                for (Node succ : graph.successors(node)) {
                    stack.push(succ);
                }
                // Also traverse predecessors for completeness (SSA values)
                for (Node pred : node.predecessors()) {
                    stack.push(pred);
                }
            }
        }
        // Also add endBlock (may not be reachable from startBlock)
        visited.add(graph.endBlock());
        return visited;
    }

    private Set<Node> computeUse(Node node) {
        // For most nodes, use = all predecessors
        // For ConstIntNode, StartNode, Block: use = empty
        if (node instanceof ConstIntNode || node instanceof StartNode || node instanceof Block) {
            return Set.of();
        }
        // For Phi, use = all operands (predecessors)
        if (node instanceof Phi) {
            return new HashSet<>(node.predecessors());
        }
        // For ProjNode, use = its input
        if (node instanceof ProjNode) {
            return Set.of(node.predecessor(ProjNode.IN));
        }
        // For ReturnNode, use = result and side effect
        if (node instanceof ReturnNode) {
            return Set.of(node.predecessor(ReturnNode.RESULT), node.predecessor(ReturnNode.SIDE_EFFECT));
        }
        // For all other nodes, use = all predecessors
        return new HashSet<>(node.predecessors());
    }

    private Set<Node> computeDef(Node node) {
        // For Block, ConstIntNode, StartNode: not a value, so not a def
        if (node instanceof ConstIntNode || node instanceof StartNode || node instanceof Block) {
            return Set.of();
        }
        // For all other nodes, the node itself is a def
        return Set.of(node);
    }

    public Set<Node> getLiveIn(Node node) {
        return liveIn.getOrDefault(node, Set.of());
    }

    public Set<Node> getLiveOut(Node node) {
        return liveOut.getOrDefault(node, Set.of());
    }
} 