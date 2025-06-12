package edu.kit.kastel.vads.compiler.ir.node;

public final class NotEqualNode extends BinaryOperationNode {
    public NotEqualNode(Block block, Node left, Node right) {
        super(block, left, right);
    }
} 