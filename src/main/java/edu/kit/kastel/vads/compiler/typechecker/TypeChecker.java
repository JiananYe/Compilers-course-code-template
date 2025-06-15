package edu.kit.kastel.vads.compiler.typechecker;

import edu.kit.kastel.vads.compiler.parser.ast.*;
import edu.kit.kastel.vads.compiler.parser.type.BasicType;
import edu.kit.kastel.vads.compiler.parser.type.Type;
import edu.kit.kastel.vads.compiler.parser.visitor.Visitor;
import edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class TypeChecker implements Visitor<Void, Type> {
    private final Map<String, Type> variables = new HashMap<>();
    private final Stack<Boolean> inLoop = new Stack<>();
    private boolean hasReturn = false;

    public TypeChecker() {
        inLoop.push(false);
    }

    @Override
    public Type visit(ProgramTree tree, Void data) {
        tree.topLevelTrees().forEach(f -> f.accept(this, data));
        if (!hasReturn) {
            throw new TypeCheckException("Program must have a return statement");
        }
        return BasicType.INT;
    }

    @Override
    public Type visit(FunctionTree tree, Void data) {
        hasReturn = false;
        tree.body().accept(this, data);
        if (!hasReturn) {
            throw new TypeCheckException("Function must have a return statement");
        }
        return tree.returnType().type();
    }

    @Override
    public Type visit(BlockTree tree, Void data) {
        Map<String, Type> oldVariables = new HashMap<>(variables);
        for (StatementTree stmt : tree.statements()) {
            stmt.accept(this, data);
        }
        variables.clear();
        variables.putAll(oldVariables);
        return null;
    }

    @Override
    public Type visit(DeclarationTree tree, Void data) {
        if (tree.initializer() != null) {
            Type initType = tree.initializer().accept(this, data);
            if (!initType.equals(tree.type().type())) {
                throw new TypeCheckException("Type mismatch in declaration initializer");
            }
        }
        variables.put(tree.name().name().asString(), tree.type().type());
        return null;
    }

    @Override
    public Type visit(AssignmentTree tree, Void data) {
        Type lvalueType = tree.lValue().accept(this, data);
        Type exprType = tree.expression().accept(this, data);
        if (!lvalueType.equals(exprType)) {
            throw new TypeCheckException("Type mismatch in assignment");
        }
        return null;
    }

    @Override
    public Type visit(ReturnTree tree, Void data) {
        Type exprType = tree.expression().accept(this, data);
        if (!exprType.equals(BasicType.INT)) {
            throw new TypeCheckException("Return expression must be of type int");
        }
        hasReturn = true;
        return null;
    }

    @Override
    public Type visit(IfTree tree, Void data) {
        Type condType = tree.condition().accept(this, data);
        if (!condType.equals(BasicType.BOOL)) {
            throw new TypeCheckException("If condition must be of type bool");
        }
        tree.thenBranch().accept(this, data);
        if (tree.elseBranch() != null) {
            tree.elseBranch().accept(this, data);
        }
        return null;
    }

    @Override
    public Type visit(WhileTree tree, Void data) {
        Type condType = tree.condition().accept(this, data);
        if (!condType.equals(BasicType.BOOL)) {
            throw new TypeCheckException("While condition must be of type bool");
        }
        inLoop.push(true);
        tree.body().accept(this, data);
        inLoop.pop();
        return null;
    }

    @Override
    public Type visit(ForTree tree, Void data) {
        if (tree.initializer() != null) {
            tree.initializer().accept(this, data);
        }
        Type condType = tree.condition().accept(this, data);
        if (!condType.equals(BasicType.BOOL)) {
            throw new TypeCheckException("For condition must be of type bool");
        }
        inLoop.push(true);
        tree.body().accept(this, data);
        if (tree.step() != null) {
            tree.step().accept(this, data);
        }
        inLoop.pop();
        return null;
    }

    @Override
    public Type visit(BreakTree tree, Void data) {
        if (!inLoop.peek()) {
            throw new TypeCheckException("Break statement must be inside a loop");
        }
        return null;
    }

    @Override
    public Type visit(ContinueTree tree, Void data) {
        if (!inLoop.peek()) {
            throw new TypeCheckException("Continue statement must be inside a loop");
        }
        return null;
    }

    @Override
    public Type visit(NegateTree tree, Void data) {
        Type exprType = tree.expression().accept(this, data);
        if (!exprType.equals(BasicType.INT)) {
            throw new TypeCheckException("Negation operator requires integer operand");
        }
        return BasicType.INT;
    }

    @Override
    public Type visit(BinaryOperationTree tree, Void data) {
        Type leftType = tree.lhs().accept(this, data);
        Type rightType = tree.rhs().accept(this, data);

        return switch (tree.operatorType()) {
            case OperatorType.LOGICAL_OR, OperatorType.LOGICAL_AND -> {
                if (!leftType.equals(BasicType.BOOL) || !rightType.equals(BasicType.BOOL)) {
                    throw new TypeCheckException("Logical operators require boolean operands");
                }
                yield BasicType.BOOL;
            }
            case OperatorType.BIT_OR, OperatorType.BIT_XOR, OperatorType.BIT_AND, OperatorType.SHIFT_LEFT, OperatorType.SHIFT_RIGHT -> {
                if (!leftType.equals(BasicType.INT) || !rightType.equals(BasicType.INT)) {
                    throw new TypeCheckException("Bitwise operators require integer operands");
                }
                yield BasicType.INT;
            }
            case OperatorType.EQUAL, OperatorType.NOT_EQUAL -> {
                if (!leftType.equals(rightType)) {
                    throw new TypeCheckException("Equality operators require operands of the same type");
                }
                yield BasicType.BOOL;
            }
            case OperatorType.LESS, OperatorType.LESS_EQUAL, OperatorType.GREATER, OperatorType.GREATER_EQUAL -> {
                if (!leftType.equals(BasicType.INT) || !rightType.equals(BasicType.INT)) {
                    throw new TypeCheckException("Comparison operators require integer operands");
                }
                yield BasicType.BOOL;
            }
            case OperatorType.PLUS, OperatorType.MINUS, OperatorType.MUL, OperatorType.DIV, OperatorType.MOD -> {
                if (!leftType.equals(BasicType.INT) || !rightType.equals(BasicType.INT)) {
                    throw new TypeCheckException("Arithmetic operators require integer operands");
                }
                yield BasicType.INT;
            }
            default -> throw new TypeCheckException("Unknown operator: " + tree.operatorType());
        };
    }

    @Override
    public Type visit(UnaryOperationTree tree, Void data) {
        Type operandType = tree.operand().accept(this, data);
        return switch (tree.operator()) {
            case OperatorType.LOGICAL_NOT -> {
                if (!operandType.equals(BasicType.BOOL)) {
                    throw new TypeCheckException("Logical not requires boolean operand");
                }
                yield BasicType.BOOL;
            }
            case OperatorType.BIT_NOT, OperatorType.MINUS -> {
                if (!operandType.equals(BasicType.INT)) {
                    throw new TypeCheckException("Bitwise not and unary minus require integer operand");
                }
                yield BasicType.INT;
            }
            default -> throw new TypeCheckException("Unknown unary operator: " + tree.operator());
        };
    }

    @Override
    public Type visit(TernaryTree tree, Void data) {
        Type condType = tree.condition().accept(this, data);
        Type thenType = tree.thenExpr().accept(this, data);
        Type elseType = tree.elseExpr().accept(this, data);
        if (!condType.equals(BasicType.BOOL)) {
            throw new TypeCheckException("Ternary condition must be of type bool");
        }
        if (!thenType.equals(elseType)) {
            throw new TypeCheckException("Ternary branches must have the same type");
        }
        return thenType;
    }

    @Override
    public Type visit(BooleanLiteralTree tree, Void data) {
        return BasicType.BOOL;
    }

    @Override
    public Type visit(IdentExpressionTree tree, Void data) {
        String name = tree.name().name().asString();
        Type type = variables.get(name);
        if (type == null) {
            throw new TypeCheckException("Undefined variable: " + name);
        }
        return type;
    }

    @Override
    public Type visit(LiteralTree tree, Void data) {
        return BasicType.INT;
    }

    @Override
    public Type visit(LValueIdentTree tree, Void data) {
        String name = tree.name().name().asString();
        Type type = variables.get(name);
        if (type == null) {
            throw new TypeCheckException("Undefined variable: " + name);
        }
        return type;
    }

    @Override
    public Type visit(NameTree tree, Void data) {
        return null;
    }

    @Override
    public Type visit(TypeTree tree, Void data) {
        return null;
    }
} 