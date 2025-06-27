package edu.kit.kastel.vads.compiler;

import edu.kit.kastel.vads.compiler.codegen.CodeGeneratorL3;
import edu.kit.kastel.vads.compiler.lexer.Lexer;
import edu.kit.kastel.vads.compiler.parser.ParseException;
import edu.kit.kastel.vads.compiler.parser.Parser;
import edu.kit.kastel.vads.compiler.parser.TokenSource;
import edu.kit.kastel.vads.compiler.parser.ast.ProgramTree;
import edu.kit.kastel.vads.compiler.typechecker.TypeChecker;
import edu.kit.kastel.vads.compiler.typechecker.TypeCheckException;
import edu.kit.kastel.vads.compiler.semantic.SemanticAnalysis;
import edu.kit.kastel.vads.compiler.semantic.SemanticException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Invalid arguments: Expected one input file and one output file");
            System.exit(3);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        
        // Lex and parse
        ProgramTree program = lexAndParse(input);
        
        // Type check
        try {
            new TypeChecker().visit(program, null);
        } catch (TypeCheckException e) {
            e.printStackTrace();
            System.exit(7);
            return;
        }

        // Semantic analysis
        try {
            new SemanticAnalysis(program).analyze();
        } catch (SemanticException e) {
            e.printStackTrace();
            System.exit(7);
            return;
        }

        // Generate code from AST using CodeGeneratorL3
        CodeGeneratorL3 codegen = new CodeGeneratorL3();
        program.accept(codegen, null);
        String assembly = codegen.getCode();
        
        // Write assembly to file
        Path asmFile = output.resolveSibling(output.getFileName() + ".s");
        Files.writeString(asmFile, assembly);

        // Compile with GCC
        ProcessBuilder pb = new ProcessBuilder("gcc", "-o", output.toString(), asmFile.toString());
        try {
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                System.err.println("GCC compilation failed with exit code " + exitCode);
                System.exit(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("GCC compilation was interrupted");
            System.exit(1);
        }
    }

    private static ProgramTree lexAndParse(Path input) throws IOException {
        try {
            Lexer lexer = Lexer.forString(Files.readString(input));
            TokenSource tokenSource = new TokenSource(lexer);
            Parser parser = new Parser(tokenSource);
            return parser.parseProgram();
        } catch (ParseException e) {
            e.printStackTrace();
            System.exit(42);
            throw new AssertionError("unreachable");
        }
    }
}