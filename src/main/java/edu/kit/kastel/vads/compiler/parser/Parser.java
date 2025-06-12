package edu.kit.kastel.vads.compiler.parser;

import edu.kit.kastel.vads.compiler.lexer.Identifier;
import edu.kit.kastel.vads.compiler.lexer.Keyword;
import edu.kit.kastel.vads.compiler.lexer.KeywordType;
import edu.kit.kastel.vads.compiler.lexer.NumberLiteral;
import edu.kit.kastel.vads.compiler.lexer.Operator;
import edu.kit.kastel.vads.compiler.lexer.Operator.OperatorType;
import edu.kit.kastel.vads.compiler.lexer.Separator;
import edu.kit.kastel.vads.compiler.lexer.Separator.SeparatorType;
import edu.kit.kastel.vads.compiler.Span;
import edu.kit.kastel.vads.compiler.lexer.Token;
import edu.kit.kastel.vads.compiler.parser.ast.AssignmentTree;
import edu.kit.kastel.vads.compiler.parser.ast.BinaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.BlockTree;
import edu.kit.kastel.vads.compiler.parser.ast.DeclarationTree;
import edu.kit.kastel.vads.compiler.parser.ast.ExpressionTree;
import edu.kit.kastel.vads.compiler.parser.ast.FunctionTree;
import edu.kit.kastel.vads.compiler.parser.ast.IdentExpressionTree;
import edu.kit.kastel.vads.compiler.parser.ast.LValueIdentTree;
import edu.kit.kastel.vads.compiler.parser.ast.LValueTree;
import edu.kit.kastel.vads.compiler.parser.ast.LiteralTree;
import edu.kit.kastel.vads.compiler.parser.ast.NameTree;
import edu.kit.kastel.vads.compiler.parser.ast.NegateTree;
import edu.kit.kastel.vads.compiler.parser.ast.ProgramTree;
import edu.kit.kastel.vads.compiler.parser.ast.ReturnTree;
import edu.kit.kastel.vads.compiler.parser.ast.StatementTree;
import edu.kit.kastel.vads.compiler.parser.ast.TypeTree;
import edu.kit.kastel.vads.compiler.parser.symbol.Name;
import edu.kit.kastel.vads.compiler.parser.type.BasicType;

import edu.kit.kastel.vads.compiler.parser.ast.IfTree;
import edu.kit.kastel.vads.compiler.parser.ast.WhileTree;
import edu.kit.kastel.vads.compiler.parser.ast.ForTree;
import edu.kit.kastel.vads.compiler.parser.ast.BreakTree;
import edu.kit.kastel.vads.compiler.parser.ast.ContinueTree;
import edu.kit.kastel.vads.compiler.parser.ast.UnaryOperationTree;
import edu.kit.kastel.vads.compiler.parser.ast.TernaryTree;
import edu.kit.kastel.vads.compiler.parser.ast.BooleanLiteralTree;
import edu.kit.kastel.vads.compiler.lexer.BooleanLiteral;

import java.util.ArrayList;
import java.util.List;

public class Parser {
    private final TokenSource tokenSource;

    public Parser(TokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    public ProgramTree parseProgram() {
        ProgramTree programTree = new ProgramTree(List.of(parseFunction()));
        if (this.tokenSource.hasMore()) {
            throw new ParseException("expected end of input but got " + this.tokenSource.peek());
        }
        return programTree;
    }

    private FunctionTree parseFunction() {
        Keyword returnType = this.tokenSource.expectKeyword(KeywordType.INT);
        Identifier identifier = this.tokenSource.expectIdentifier();
        this.tokenSource.expectSeparator(SeparatorType.PAREN_OPEN);
        this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
        BlockTree body = parseBlock();
        return new FunctionTree(
            new TypeTree(BasicType.INT, returnType.span()),
            name(identifier),
            body
        );
    }

    private BlockTree parseBlock() {
        Separator bodyOpen = this.tokenSource.expectSeparator(SeparatorType.BRACE_OPEN);
        List<StatementTree> statements = new ArrayList<>();
        while (!(this.tokenSource.peek() instanceof Separator sep && sep.type() == SeparatorType.BRACE_CLOSE)) {
            statements.add(parseStatement());
        }
        Separator bodyClose = this.tokenSource.expectSeparator(SeparatorType.BRACE_CLOSE);
        return new BlockTree(statements, bodyOpen.span().merge(bodyClose.span()));
    }

    private StatementTree parseStatement() {
        StatementTree statement;
        if (this.tokenSource.peek().isKeyword(KeywordType.INT) || this.tokenSource.peek().isKeyword(KeywordType.BOOL)) {
            statement = parseDeclaration();
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        } else if (this.tokenSource.peek().isKeyword(KeywordType.RETURN)) {
            statement = parseReturn();
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        } else if (this.tokenSource.peek().isKeyword(KeywordType.IF)) {
            statement = parseIf();
        } else if (this.tokenSource.peek().isKeyword(KeywordType.WHILE)) {
            statement = parseWhile();
        } else if (this.tokenSource.peek().isKeyword(KeywordType.FOR)) {
            statement = parseFor();
        } else if (this.tokenSource.peek().isKeyword(KeywordType.BREAK)) {
            statement = parseBreak();
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        } else if (this.tokenSource.peek().isKeyword(KeywordType.CONTINUE)) {
            statement = parseContinue();
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        } else if (this.tokenSource.peek().isSeparator(SeparatorType.BRACE_OPEN)) {
            statement = parseBlock();
        } else {
            statement = parseSimple();
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        }
        return statement;
    }

    private StatementTree parseDeclaration() {
        Keyword type = this.tokenSource.peek().isKeyword(KeywordType.INT) 
            ? this.tokenSource.expectKeyword(KeywordType.INT)
            : this.tokenSource.expectKeyword(KeywordType.BOOL);
        Identifier ident = this.tokenSource.expectIdentifier();
        ExpressionTree expr = null;
        if (this.tokenSource.peek().isOperator(OperatorType.ASSIGN)) {
            this.tokenSource.expectOperator(OperatorType.ASSIGN);
            expr = parseExpression();
        }
        return new DeclarationTree(
            new TypeTree(type.type() == KeywordType.INT ? BasicType.INT : BasicType.BOOL, type.span()),
            name(ident),
            expr
        );
    }

    private StatementTree parseSimple() {
        LValueTree lValue = parseLValue();
        Operator assignmentOperator = parseAssignmentOperator();
        ExpressionTree expression = parseExpression();
        return new AssignmentTree(lValue, assignmentOperator, expression);
    }

    private StatementTree parseIf() {
        Keyword ifKeyword = this.tokenSource.expectKeyword(KeywordType.IF);
        this.tokenSource.expectSeparator(SeparatorType.PAREN_OPEN);
        ExpressionTree condition = parseExpression();
        this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
        StatementTree thenBranch = parseStatement();
        StatementTree elseBranch = null;
        if (this.tokenSource.peek().isKeyword(KeywordType.ELSE)) {
            this.tokenSource.expectKeyword(KeywordType.ELSE);
            elseBranch = parseStatement();
        }
        return new IfTree(condition, thenBranch, elseBranch, ifKeyword.span());
    }

    private StatementTree parseWhile() {
        Keyword whileKeyword = this.tokenSource.expectKeyword(KeywordType.WHILE);
        this.tokenSource.expectSeparator(SeparatorType.PAREN_OPEN);
        ExpressionTree condition = parseExpression();
        this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
        StatementTree body = parseStatement();
        return new WhileTree(condition, body, whileKeyword.span());
    }

    private StatementTree parseFor() {
        Keyword forKeyword = this.tokenSource.expectKeyword(KeywordType.FOR);
        this.tokenSource.expectSeparator(SeparatorType.PAREN_OPEN);
        StatementTree initializer = parseForInitializer();
        this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        ExpressionTree condition = parseExpression();
        this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        StatementTree step = parseForStep();
        this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
        StatementTree body = parseStatement();
        return new ForTree(initializer, condition, step, body, forKeyword.span());
    }

    private StatementTree parseForHeaderStatement() {
        // If the next token is a separator, there is no statement here
        if (this.tokenSource.peek() instanceof Separator) {
            return null;
        }
        if (this.tokenSource.peek().isKeyword(KeywordType.INT) || this.tokenSource.peek().isKeyword(KeywordType.BOOL)) {
            StatementTree decl = parseDeclaration();
            return decl;
        } else if (this.tokenSource.peek() instanceof Identifier) {
            LValueTree lValue = parseLValue();
            if (this.tokenSource.peek() instanceof Operator) {
                Operator assignmentOperator = parseAssignmentOperator();
                ExpressionTree expression = parseExpression();
                return new AssignmentTree(lValue, assignmentOperator, expression);
            }
        }
        return null;
    }

    private StatementTree parseForInitializer() {
        StatementTree initializer = parseForHeaderStatement();
        if (initializer != null && !(initializer instanceof DeclarationTree)) {
            this.tokenSource.expectSeparator(SeparatorType.SEMICOLON);
        }
        return initializer;
    }

    private StatementTree parseForStep() {
        return parseForHeaderStatement();
    }

    private StatementTree parseBreak() {
        Keyword breakKeyword = this.tokenSource.expectKeyword(KeywordType.BREAK);
        return new BreakTree(breakKeyword.span());
    }

    private StatementTree parseContinue() {
        Keyword continueKeyword = this.tokenSource.expectKeyword(KeywordType.CONTINUE);
        return new ContinueTree(continueKeyword.span());
    }

    private Operator parseAssignmentOperator() {
        if (this.tokenSource.peek() instanceof Operator op) {
            return switch (op.type()) {
                case ASSIGN, ASSIGN_DIV, ASSIGN_MINUS, ASSIGN_MOD, ASSIGN_MUL, ASSIGN_PLUS,
                     ASSIGN_BIT_AND, ASSIGN_BIT_XOR, ASSIGN_BIT_OR,
                     ASSIGN_SHIFT_LEFT, ASSIGN_SHIFT_RIGHT -> {
                    this.tokenSource.consume();
                    yield op;
                }
                default -> throw new ParseException("expected assignment but got " + op.type());
            };
        }
        throw new ParseException("expected assignment but got " + this.tokenSource.peek());
    }

    private ExpressionTree parseExpression() {
        return parseLogicalOr();
    }

    private ExpressionTree parseLogicalOr() {
        ExpressionTree lhs = parseLogicalAnd();
        while (true) {
            if (this.tokenSource.peek().isOperator(OperatorType.LOGICAL_OR)) {
                this.tokenSource.consume();
                lhs = new BinaryOperationTree(lhs, parseLogicalAnd(), OperatorType.LOGICAL_OR);
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseLogicalAnd() {
        ExpressionTree lhs = parseBitwiseOr();
        while (true) {
            if (this.tokenSource.peek().isOperator(OperatorType.LOGICAL_AND)) {
                this.tokenSource.consume();
                lhs = new BinaryOperationTree(lhs, parseBitwiseOr(), OperatorType.LOGICAL_AND);
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseBitwiseOr() {
        ExpressionTree lhs = parseBitwiseXor();
        while (true) {
            if (this.tokenSource.peek().isOperator(OperatorType.BIT_OR)) {
                this.tokenSource.consume();
                lhs = new BinaryOperationTree(lhs, parseBitwiseXor(), OperatorType.BIT_OR);
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseBitwiseXor() {
        ExpressionTree lhs = parseBitwiseAnd();
        while (true) {
            if (this.tokenSource.peek().isOperator(OperatorType.BIT_XOR)) {
                this.tokenSource.consume();
                lhs = new BinaryOperationTree(lhs, parseBitwiseAnd(), OperatorType.BIT_XOR);
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseBitwiseAnd() {
        ExpressionTree lhs = parseEquality();
        while (true) {
            if (this.tokenSource.peek().isOperator(OperatorType.BIT_AND)) {
                this.tokenSource.consume();
                lhs = new BinaryOperationTree(lhs, parseEquality(), OperatorType.BIT_AND);
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseEquality() {
        ExpressionTree lhs = parseComparison();
        while (true) {
            if (this.tokenSource.peek() instanceof Operator op) {
                if (op.type() == OperatorType.EQUAL || op.type() == OperatorType.NOT_EQUAL) {
                    this.tokenSource.consume();
                    lhs = new BinaryOperationTree(lhs, parseComparison(), op.type());
                } else {
                    return lhs;
                }
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseComparison() {
        ExpressionTree lhs = parseShift();
        while (true) {
            if (this.tokenSource.peek() instanceof Operator op) {
                if (op.type() == OperatorType.LESS || op.type() == OperatorType.LESS_EQUAL ||
                    op.type() == OperatorType.GREATER || op.type() == OperatorType.GREATER_EQUAL) {
                    this.tokenSource.consume();
                    lhs = new BinaryOperationTree(lhs, parseShift(), op.type());
                } else {
                    return lhs;
                }
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseShift() {
        ExpressionTree lhs = parseAdditive();
        while (true) {
            if (this.tokenSource.peek() instanceof Operator op) {
                if (op.type() == OperatorType.SHIFT_LEFT || op.type() == OperatorType.SHIFT_RIGHT) {
                    this.tokenSource.consume();
                    lhs = new BinaryOperationTree(lhs, parseAdditive(), op.type());
                } else {
                    return lhs;
                }
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseAdditive() {
        ExpressionTree lhs = parseMultiplicative();
        while (true) {
            if (this.tokenSource.peek() instanceof Operator op) {
                if (op.type() == OperatorType.PLUS || op.type() == OperatorType.MINUS) {
                    this.tokenSource.consume();
                    lhs = new BinaryOperationTree(lhs, parseMultiplicative(), op.type());
                } else {
                    return lhs;
                }
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseMultiplicative() {
        ExpressionTree lhs = parseUnary();
        while (true) {
            if (this.tokenSource.peek() instanceof Operator op) {
                if (op.type() == OperatorType.MUL || op.type() == OperatorType.DIV || op.type() == OperatorType.MOD) {
                    this.tokenSource.consume();
                    lhs = new BinaryOperationTree(lhs, parseUnary(), op.type());
                } else {
                    return lhs;
                }
            } else {
                return lhs;
            }
        }
    }

    private ExpressionTree parseUnary() {
        if (this.tokenSource.peek() instanceof Operator op) {
            return switch (op.type()) {
                case MINUS -> {
                    this.tokenSource.consume();
                    yield new NegateTree(parseUnary(), op.span());
                }
                case LOGICAL_NOT -> {
                    this.tokenSource.consume();
                    yield new UnaryOperationTree(parseUnary(), OperatorType.LOGICAL_NOT, op.span());
                }
                case BIT_NOT -> {
                    this.tokenSource.consume();
                    yield new UnaryOperationTree(parseUnary(), OperatorType.BIT_NOT, op.span());
                }
                default -> parseTernary();
            };
        }
        return parseTernary();
    }

    private ExpressionTree parseTernary() {
        ExpressionTree condition = parsePrimary();
        if (this.tokenSource.peek().isOperator(OperatorType.QUESTION)) {
            this.tokenSource.consume();
            ExpressionTree thenExpr = parseExpression();
            this.tokenSource.expectOperator(OperatorType.COLON);
            ExpressionTree elseExpr = parseExpression();
            return new TernaryTree(condition, thenExpr, elseExpr, condition.span());
        }
        return condition;
    }

    private ExpressionTree parsePrimary() {
        return switch (this.tokenSource.peek()) {
            case Separator(var type, _) when type == SeparatorType.PAREN_OPEN -> {
                this.tokenSource.consume();
                ExpressionTree expression = parseExpression();
                this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
                yield expression;
            }
            case Identifier ident -> {
                this.tokenSource.consume();
                yield new IdentExpressionTree(name(ident));
            }
            case NumberLiteral(String value, int base, Span span) -> {
                this.tokenSource.consume();
                yield new LiteralTree(value, base, span);
            }
            case BooleanLiteral(boolean value, Span span) -> {
                this.tokenSource.consume();
                yield new BooleanLiteralTree(value, span);
            }
            case Token t -> throw new ParseException("invalid primary expression " + t);
        };
    }

    private LValueTree parseLValue() {
        if (this.tokenSource.peek().isSeparator(SeparatorType.PAREN_OPEN)) {
            this.tokenSource.expectSeparator(SeparatorType.PAREN_OPEN);
            LValueTree inner = parseLValue();
            this.tokenSource.expectSeparator(SeparatorType.PAREN_CLOSE);
            return inner;
        }
        Identifier identifier = this.tokenSource.expectIdentifier();
        return new LValueIdentTree(name(identifier));
    }

    private StatementTree parseReturn() {
        Keyword ret = this.tokenSource.expectKeyword(KeywordType.RETURN);
        ExpressionTree expression = parseExpression();
        return new ReturnTree(expression, ret.span().start());
    }

    private static NameTree name(Identifier ident) {
        return new NameTree(Name.forIdentifier(ident), ident.span());
    }
}
