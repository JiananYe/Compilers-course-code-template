package edu.kit.kastel.vads.compiler.ir.node;

import java.util.List;

public final class CallNode extends Node {
    private final String callee;
    private final List<Node> arguments;

    public CallNode(Block block, String callee, List<Node> arguments) {
        super(block, arguments.toArray(new Node[0]));
        this.callee = callee;
        this.arguments = List.copyOf(arguments);
    }

    public String getCallee() {
        return callee;
    }

    public List<Node> getArguments() {
        return arguments;
    }

    @Override
    protected String info() {
        return callee + "(" + arguments.size() + " args)";
    }
} 