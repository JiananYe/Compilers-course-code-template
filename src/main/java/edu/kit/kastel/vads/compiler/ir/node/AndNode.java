package edu.kit.kastel.vads.compiler.ir.node;

public final class AndNode extends BinaryOperationNode {
    public AndNode(Block block, Node left, Node right) {
        super(block, left, right);
    }
} 