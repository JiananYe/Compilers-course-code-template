package edu.kit.kastel.vads.compiler.ir.node;

public final class OrNode extends BinaryOperationNode {
    public OrNode(Block block, Node left, Node right) {
        super(block, left, right);
    }
} 