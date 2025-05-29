L2 Syntax
 The concrete syntax of L2 is based on ASCII character encoding of source code.
 Lexical Tokens
 L2 has some additional unary and binary operators; you can find them as terminals in the asnop, unop, binop produc
 tions of Listing 1.
 The reserved keywords in L2 are the same as in L1:
 struct if else while for continue break return assert true false NULL print read alloc alloc_array int bool
 void char string.
 Whitespace and Token Delimiting
 In L2, whitespace is either a space, horizontal tab (\t), carriage return (\r), or linefeed (\n) character in ASCII
 encoding. Whitespace is ignored, except that it terminates tokens. Note that whitespace in not a requirement to
 terminate a token. For instance, () should be tokenized into a left parenthesis followed by a right parenthesis
 according to the given lexical specification. The lexer should produce the longest valid token possible. Therefore, +=
 is one token while + = is two tokens.
 Comments
 L2 source programs may contain Cstyle comments of the form /* ... */ for multi line comments and // for single
 line comments. Multi line comments may be nested (and of course the delimiters must be balanced.)
 Grammar
 The syntax of L2 is defined by the context free grammar in Listing 1. Ambiguities in this grammar are resolved
 according to the operator precedence table in Table 1. There is one leftover ambiguity in this grammar, commonly
 referred to as the “danglingelse” problem. It occurs in if chains terminating with a single else, like if (a) if (b)
 else. This code can be parsed in two ways:
 if (a) {  
  if (b) {
  } else {
  }       
}
 if (a) {
  if (b) {
  }
 } else {
 }
 In L2, we attribute the else to the closest if without an else, i.e. the first option on the left.
 Operator Associates Meaning
 () n/a explicit parentheses
 ! ~ -right logical not, bitwise not, unary minus
 * / % left integer times, divide, modulo
 + -left integer plus, minus
 << >> left (arithmetic) shift left, right
 < <= > >= left integer comparison
 == != left overloaded equality, disequality
 & left bitwise and
 ^ left bitwise exclusive or
 | left bitwise or
 && left logical and
 || left logical or
 ? : right conditional expression
 = += -= *= /= %= &= ^= |= <<= >>= right assignment operators
 Table 1: Precedence of unary and binary operators in L2, from highest to lowest.
 2
⟨program⟩ ⩴ int main ( ) ⟨block⟩
 ⟨block⟩
 ⩴ { ⟨stmts⟩ }
 ⟨type⟩
 ⟨decl⟩
 ⟨stmts⟩
 ⟨stmt⟩
 ⟨simp⟩
 ⩴ int | bool
 ⩴ ⟨type⟩ ident
 |
 ⟨type⟩ ident = ⟨exp⟩
 ⩴ 𝜀 | ⟨stmt⟩ ⟨stmts⟩
 ⩴ ⟨simp⟩ ; | ⟨control⟩ | ⟨block⟩
 ⩴ ⟨lvalue⟩ ⟨asnop⟩ ⟨exp⟩
 |
 ⟨decl⟩
 ⟨simpopt⟩ ⩴ 𝜀 | ⟨simp⟩
 ⟨lvalue⟩
 ⩴ ident | ( ⟨lvalue⟩ )
 ⟨elseopt⟩ ⩴ 𝜀 | else ⟨stmt⟩
 ⟨control⟩ ⩴ if ( ⟨exp⟩ ) ⟨stmt⟩ ⟨elseopt⟩
 |
 while ( ⟨exp⟩ ) ⟨stmt⟩
 |
 |
 ⟨exp⟩
 for ( ⟨simpopt⟩ ; ⟨exp⟩ ; ⟨simpopt⟩ ) ⟨stmt⟩
 continue ; | break ; | return ⟨exp⟩ ;
 ⩴ true | false | ident
 |
 ( ⟨exp⟩ ) | ⟨intconst⟩
 |
 |
 |
 ⟨exp⟩ ⟨binop⟩ ⟨exp⟩
 ⟨unop⟩ ⟨exp⟩
 ⟨exp⟩ ? ⟨exp⟩ : ⟨exp⟩
 ⟨intconst⟩ ⩴ decnum | hexnum
 ⟨unop⟩
 ⩴ ! | ~ |
⟨asnop⟩
 ⟨binop⟩
 ident
 ⩴ = | += |-= | *= | /= | %=
 |
 &= | ^= | |= | <<= | >>=
 ⩴ + |- | * | / | % | < | <= | > | >=
 |
 == | != | && | || | & | ^ | | | << | >>
 ⩴ [A-Za-z_] [A-Za-z0-9_]*
 decnum ⩴ 0 | [1-9] [0-9]*
 hexnum ⩴ 0 [xX] [A-Fa-f0-9]+
 Listing 1: Grammar of L2, nonterminals in ⟨brackets⟩, terminals in bold.
 L2 Elaboration
 As the name intended to suggest, Elaboration is the process of elaborating the grammar that we have to one that
 is simpler and more well behaved – the abstract syntax. We describe elaboration separately because it is logically
 intended as a separate pass of compilation that happens immediately after parsing. Unfortunately, justifying the
 way we choose to elaborate a language may depend on an intuitive understanding of the operational behavior of the
 concrete language. Therefore, we recommend that you check what you see in this section against the description
 of the static and dynamic semantics of L2 that appear in the next two sections.
 Your implementation may of course employ a different elaboration strategy — but we will rely on the following
 elaboration strategy extensively in the description of the static semantics, and your implementation must behave
 in an equivalent manner. As always, document any design decisions you make.
 3
We propose the following tree structure as the abstract syntax for statements 𝑠:
 𝑠 ⩴| 𝐚𝐬𝐬𝐢𝐠𝐧(𝑥,𝑒)
 | 𝐰𝐡𝐢𝐥𝐞(𝑒,𝑠)
 | 𝐜𝐨𝐧𝐭𝐢𝐧𝐮𝐞
 | 𝐫𝐞𝐭𝐮𝐫𝐧(𝑒)
 | 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠)
 | 𝐢𝐟(𝑒, 𝑠, 𝑠)
 | 𝐟𝐨𝐫(𝑠,𝑒,𝑠,𝑠)
 | 𝐛𝐫𝐞𝐚𝐤
 | 𝐬𝐞𝐪(𝑠,𝑠)
 | 𝐧𝐨𝐩
 where 𝑒 stands for an expression, 𝑥 for an identifier, and 𝜏 for a type. Do not be confused by the fact that this looks
 like a grammar: the terms on the right hand side describe trees, not strings. The whole program is represented here
 as a single statement 𝐬𝐞𝐪(𝑠1,𝐬𝐞𝐪(𝑠2,…)). In an implementation it may be more convenient to use lists explicitly.
 Here are the suggested inference rules to elaborate blocks to trees:
 {} ⇝𝐧𝐨𝐩
 statement ⇝ 𝑠 {remaining body} ⇝ 𝑠′
 {statement;remaining body} ⇝ 𝐬𝐞𝐪(𝑠,𝑠′)
 {remaining body} ⇝ 𝑠′
 {𝜏 𝑥; remaining body} ⇝ 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠′)
 Note that for the code: 𝜏 𝑥 = 𝑒 one may be tempted to write the following elaborate rule:
 𝑒 ⇝𝑒′
 {remaining body} ⇝ 𝑠′
 {𝜏 𝑥 =𝑒; remaining body} ⇝ 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝐬𝐞𝐪(𝐚𝐬𝐬𝐢𝐠𝐧(𝑥,𝑒′),𝑠′))
 But this form of elaboration is subtly wrong! This states that the variable 𝑥 should have type 𝜏 in the expression 𝑒,
 but semantically that doesn’t quite make sense. The language specification states that variables are only available
 in the statements following their initialization. This will become more apparent in lab 3 when functions are added,
 but for the meantime, please check these elaboration rules against the scoping rules given in the static semantics.
 As can be seen from the syntax, if statements always have both a then branch and an else branch represented
 by the two statements in that order. for loops have the initializer statement, the conditional expression, the step
 statement, and the loop body in that order. Elaborating if, else, while, for, break, continue, and return should be
 fairly straightforward, and we do not give any rules for them.
 As in L1 assignment statements of the form a op= b are elaborated to be equivalent to a = a op b.
 Unlike statements, expressions are already in a compact and well behaved representation. We only elaborate the
 logical operators. a && b elaborates a ? b : false, and a || b elaborates to a ? true : b.
 L2 Static Semantics
 The following section describes the static semantics of L2.
 Type Checking
 Since our grammar and our type system have become a little more interesting, we now give some rules for type
 checking. These rules are fairly informal.
 Type-checking Statements v. Type-checking Expressions
 Our grammar allows expressions to appear within statements, but there is no way to embed statements within
 expressions. As a consequence it is meaningful to have a judgement of the form “𝑒 : 𝜏” to convey that an expression
 𝑒 has the type 𝜏, but the same is meaningless when discussing statements. Therefore, for statements, we have the
 judgement “𝑠 𝑣𝑎𝑙𝑖𝑑”.
 Variable Declarations and Contexts
 As in L1, variables need to be declared with their types before they can be used. Unlike in L1, declarations can
 appear in any block. The declaration of a variable is not visible outside the block of its declaration. Note that variables
 declared in inner blocks do not shadow variables declared in enclosing scopes. Therefore, multiple declarations of
 the same identifier may be present in the body of main if and only if no two of them are visible within the same block.
 4
The abstract syntax for declarations, i.e. 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠) is very convenient for capturing all these phenomena and
 specifying the rules of typechecking. Here, the declaration of 𝑥 of type 𝜏 is visible only to statement 𝑠, and your
 elaborator should take care to elaborate statements while correctly preserving the lexical scope of declarations.
 Making the scope explicit in the abstract syntax makes it easy for you to build up a context of variable declarations
 available while typechecking any particular statement or expression.
 We say “Γ ⊢ 𝑒 : 𝜏” when an expression 𝑒 is typechecked under the context Γ, which keeps track of all variable
 declarations and their types. The following inference rules demonstrate the function of the context.
 𝑥 : 𝜏 ∈Γ
 Γ ⊢𝑥:𝜏
 Here, 𝑥 stands for any identifier.
 𝑥 : 𝜏′ ∉ Γ for any 𝜏′ Γ,𝑥 : 𝜏 ⊢ 𝑠 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠) 𝑣𝑎𝑙𝑖𝑑
 The Types
 In L2, int is no longer the only type. It is joined by bool, which is inhabited by true and false. L2 does not allow
 implicit or explicit coercion between integral and boolean values. This is a major point of departure from C and other
 (in)famous languages.
 Statements
 We have already explained how declarations work. Here are the remaining significant rules:
 Γ ⊢𝑒:𝐛𝐨𝐨𝐥 Γ⊢𝑠1 𝑣𝑎𝑙𝑖𝑑 Γ⊢𝑠2 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝐢𝐟(𝑒,𝑠1,𝑠2) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑒:𝐛𝐨𝐨𝐥 Γ⊢𝑠 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝐰𝐡𝐢𝐥𝐞(𝑒,𝑠) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑒:𝜏 Γ,𝑥:𝜏 ⊢𝑒1 :𝐛𝐨𝐨𝐥 Γ,𝑥:𝜏 ⊢𝑠2 𝑣𝑎𝑙𝑖𝑑 Γ,𝑥:𝜏 ⊢𝑠3 𝑣𝑎𝑙𝑖𝑑 𝑥∉Γ
 Γ ⊢𝐟𝐨𝐫(𝜏 𝑥 = 𝑒,𝑒1,𝑠2,𝑠3) 𝑣𝑎𝑙𝑖𝑑
 Γ,𝑥 : 𝜏 ⊢ 𝑒1 : 𝐛𝐨𝐨𝐥 Γ,𝑥: 𝜏 ⊢𝑠2 𝑣𝑎𝑙𝑖𝑑 Γ,𝑥:𝜏 ⊢𝑠3 𝑣𝑎𝑙𝑖𝑑 𝑥∉Γ
 Γ ⊢𝐟𝐨𝐫(𝜏 𝑥,𝑒1,𝑠2,𝑠3) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑒:𝐛𝐨𝐨𝐥 Γ⊢𝑠1 𝑣𝑎𝑙𝑖𝑑 Γ⊢𝑠2 𝑣𝑎𝑙𝑖𝑑 Γ⊢𝑠3 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝐟𝐨𝐫(𝑠1,𝑒,𝑠2,𝑠3) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑥:𝜏 Γ⊢𝑒:𝜏
 Γ ⊢𝐚𝐬𝐬𝐢𝐠𝐧(𝑥,𝑒) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑠1 𝑣𝑎𝑙𝑖𝑑 Γ⊢𝑠2 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝐬𝐞𝐪(𝑠1,𝑠2) 𝑣𝑎𝑙𝑖𝑑
 Γ ⊢𝑒:𝐢𝐧𝐭
 Γ ⊢𝐫𝐞𝐭𝐮𝐫𝐧(𝑒) 𝑣𝑎𝑙𝑖𝑑
 The rule for return statements is still very rudimentary because we have only one function in the program, and it is
 required to return an int.
 Expressions
 The following are the rules to check expressions for type correctness:
 Γ ⊢𝐭𝐫𝐮𝐞 : 𝐛𝐨𝐨𝐥
 Γ ⊢𝐟𝐚𝐥𝐬𝐞 : 𝐛𝐨𝐨𝐥
 Γ ⊢𝑒1 : 𝐢𝐧𝐭 Γ⊢𝑒2 :𝐢𝐧𝐭 relop∈{<,≤,>,≥}
 Γ ⊢𝑒1 relop 𝑒2 : 𝐛𝐨𝐨𝐥
 Γ ⊢𝐢𝐧𝐭𝐜𝐨𝐧𝐬𝐭 : 𝐢𝐧𝐭
 Γ ⊢𝑒1 : 𝜏 Γ⊢𝑒2 :𝜏 polyeq∈{==,!=}
 Γ ⊢𝑒1 polyeq 𝑒2 : 𝐛𝐨𝐨𝐥
 Γ ⊢𝑒1 : 𝐛𝐨𝐨𝐥 Γ⊢𝑒2 :𝐛𝐨𝐨𝐥 logop∈{&&,||}
 Γ ⊢𝑒1 logop 𝑒2 : 𝐛𝐨𝐨𝐥
 Γ ⊢𝑒:𝐛𝐨𝐨𝐥
 Γ ⊢!𝑒 : 𝐛𝐨𝐨𝐥
 Note that in a type theoretic sense, equality and disequality are overloaded operators, not polymorphic operators.
 See the dynamic semantics to see how the implementation is affected.
 Γ ⊢𝑒1 : 𝐢𝐧𝐭 Γ⊢𝑒2 :𝐢𝐧𝐭
 Γ ⊢𝑒1 binop 𝑒2 : 𝐢𝐧𝐭
 Γ ⊢𝑒:𝐢𝐧𝐭
 Γ ⊢unop 𝑒 : 𝐢𝐧𝐭
 5
Here, binop and unop are all the remaining binary and unary operators in the grammar not covered by the rules
 for booleans.
 Γ ⊢𝑒1 : 𝐛𝐨𝐨𝐥 Γ⊢𝑒2 :𝜏 Γ⊢𝑒3 :𝜏
 Γ ⊢(𝑒1?𝑒2 : 𝑒3) : 𝜏
 Control Flow
 Regarding control flow, several properties must be checked:
 • Each (finite) control flow path through the program must terminate with an explicit return statement. This ensures
 that the program does not terminate with an undefined value.
 • Each break or continue statement must occur within a while or for loop.
 Regarding variables, we need the following:
 • On each control flow path through the program, each variable must be defined by an assignment before it is used.
 This ensures that there will be no references to uninitialized variables.
 • The step statement in a for loop may not be a declaration.
 We define these checks more rigorously on the abstract syntax as follows.
 Checking Proper Returns
 We check that all finite control flow path through a program end with an explicit return statement. We say that 𝑠
 returns if every execution of 𝑠 that terminates will always end with a return statement. Overall, we want to ensure
 that the whole program, represented as a single statement 𝑠, returns according to this definition. If not, the compiler
 must signal a semantic analysis error.
 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠) returns if 𝑠 returns
 𝐚𝐬𝐬𝐢𝐠𝐧(𝑥,𝑒)
 does not return
 𝐢𝐟(𝑒, 𝑠1, 𝑠2)
 𝐰𝐡𝐢𝐥𝐞(𝑒,𝑠)
 returns if both 𝑠1 and 𝑠2 return
 does not return
 𝐟𝐨𝐫(𝑠1,𝑒,𝑠2,𝑠3) does not return
 𝐫𝐞𝐭𝐮𝐫𝐧(𝑒)
 returns
 𝐧𝐨𝐩
 𝐬𝐞𝐪(𝑠1,𝑠2)
 does not return
 returns if either 𝑠1 returns (and therefore 𝑠2 is dead code) or 𝑠2 returns.
 We do not look inside loops (even though the bodies may contain return statements) because the body might not
 be executed at all, or might terminate by a break, not a return. Because we do not look inside loops, we do not need
 rules for break or continue.
 Checking Variable Initialization
 We wish to give a well formed deterministic dynamic semantics to L2. A program should either return a value, raise
 a divide exception, or fail to terminate. In order to do this, our static semantics must enforce the following necessary
 condition: we need to check that along all control flow paths, any variable is defined before use. Since the language
 allows the nesting of scopes of declaration, we define the variable initialization property as a local property of a scope
 which is actually stronger than the guarantee we need to make in order to give a well formed dynamic semantics to
 the entire program.
 To further streamline the specification, we transform for loops as follows:
 𝐟𝐨𝐫(initializer, 𝑒, step, body) ⇝ 𝐬𝐞𝐪(initializer,𝐰𝐡𝐢𝐥𝐞(𝑒,bodystep))
 Here bodystep is body with step inserted before every occurrence of continue that doesn’t fall within the scope of an
 inner loop, and at the very end of the loop body. Check the dynamic semantics and make sure you understand why
 this transformation preserves those semantics. You may also find it an interesting exercise to consider what would
 go wrong if you were to include this transformation in the elaborator.
 Make sure that your elaboration for the case when initializer contains a variable definition is very careful, so
 that variable initialization checking works on variables introduced in for loops. Your implementation may be more
 efficient if you convert while loops to for loops instead–a similar analysis will apply in this case.
 6
First, we specify when a statement 𝑠 defines a variable 𝑥. We read this as: Whenever 𝑠 finishes normally, it will have
 defined 𝑥. This excludes cases where 𝑠 returns, executes a break or continue statement, or does not terminate.
 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠) defines no variable
 𝐚𝐬𝐬𝐢𝐠𝐧(𝑥,𝑒)
 defines only 𝑥
 𝐢𝐟(𝑒, 𝑠1, 𝑠2)
 𝐰𝐡𝐢𝐥𝐞(𝑒,𝑠)
 𝐛𝐫𝐞𝐚𝐤
 𝐜𝐨𝐧𝐭𝐢𝐧𝐮𝐞
 𝐫𝐞𝐭𝐮𝐫𝐧(𝑒)
 𝐧𝐨𝐩
 𝐬𝐞𝐪(𝑠1,𝑠2)
 defines 𝑥 if both 𝑠1 and 𝑠2 define 𝑥
 defines no 𝑥 (because the loop body may not be executed)
 defines all 𝑥 within scope (because it transfers control out of the scope)
 defines all 𝑥 within scope (because it transfers control out of the scope)
 defines all 𝑥 within scope (because it transfers control out of the scope)
 defines no 𝑥
 defines 𝑥 if either 𝑠1 or 𝑠2 does
 We also say that an expression 𝑒 uses a variable 𝑥 if 𝑥 occurs in 𝑒. In our language, 𝑒 may have logical operators
 which will not necessarily evaluate all their arguments, but we still say that a variable occurring in such an argument
 is used, because it might be.
 We now define which variables are live in a statement 𝑠, that is, their value may be used in the execution of 𝑠.
 𝑦 is live in 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥, 𝜏, 𝑠) if 𝑦 is live in 𝑠 and 𝑦 is not the same as 𝑥
 𝑦 is live in 𝐚𝐬𝐬𝐢𝐠𝐧(𝑥, 𝑒)
 if 𝑦 is used in 𝑒
 𝑦 is live in 𝐢𝐟(𝑒, 𝑠1, 𝑠2)
 𝑦 is live in 𝐰𝐡𝐢𝐥𝐞(𝑒, 𝑠)
 𝑦 is live in 𝐛𝐫𝐞𝐚𝐤
 𝑦 is live in 𝐜𝐨𝐧𝐭𝐢𝐧𝐮𝐞
 𝑦 is live in 𝐫𝐞𝐭𝐮𝐫𝐧(𝑒)
 𝑦 is live in 𝐧𝐨𝐩
 𝑦 is live in 𝐬𝐞𝐪(𝑠1, 𝑠2)
 if 𝑦 is used in 𝑒 or live in 𝑠1 or 𝑠2
 if 𝑦 is used in 𝑒 or live in 𝑠
 never (the jump target is accounted for elsewhere)
 never (the jump target is accounted for elsewhere)
 if 𝑦 is used in 𝑒
 never
 if 𝑦 is live in 𝑠1 or 𝑦 is live in 𝑠2 and not defined in 𝑠1
 Since scopes are encoded as declare statements, the given strategy has also told us what variables are live at
 the beginning of a scope, i.e. not initialized before its first use. Static analysis should reject a program if for any
 𝐝𝐞𝐜𝐥𝐚𝐫𝐞(𝑥,𝜏,𝑠), the variable 𝑥 is live in 𝑠.
 The following example demonstrates how our static analysis is sufficient to guarantee deterministic evaluation:
 { int x; int y; return 1; x = y + 1 ; }
 is valid because the statement x = y + 1 cannot be reached along any control flow path from the beginning of the
 program. Formally, the statement return 1 is taken to define all variables, including y, so that y is not live in the
 whole program even though it is live in the second statement.
 The following example demonstrates how our static analysis is stronger than a sufficient condition to guarantee
 deterministic evaluation:
 { int x; return 1; { int y; x = y + 1; } }
 We can still give a well formed dynamic semantics for the program. However, static analysis will raise an error
 because the following block is not well formed:
 { int y; x = y + 1; }
 Make sure that you have checked that all control flow paths return and all occurrences of break and continue are
 inside loops before checking for variable initialization. Otherwise, the effect that these statements have on liveness
 may produce very unhelpful and cryptic error messages for ill formed programs.
 L2 Dynamic Semantics
 In most cases, statements have the familiar operational semantics from C. Conditionals, for, and while loops
 execute as in C. continue skips the rest of the statements in a loop body and break jumps to the first statement after
 7
a loop. As in C, when encountering a continue inside a for lop, we jump to the step statement. The suggested method
 of elaboration reflects this behavior. Both break and continue always apply to the innermost loop the occur in.
 The ternary operator (?:), as in C, must only evaluate the branch that is actually taken. The suggested elaboration
 of the logical operators to the ternary operator also reflect their C like shortcircuit evaluation.
 Integer Operations
 Since expressions do not have effects (except for a possible divide exception that might be raised) the order of their
 evaluation is irrelevant.
 The integers of this language are signed integers in two’s complement representation with a word size of 32 bits.
 The semantics of the operations is given by modular arithmetic, as in L1. Recall that division by zero and division
 overflow must raise a runtime division exception. This is the only runtime exception that should be possible in the
 language for now.
 As in L1, decimal constants 𝑐 in a program must be in the range 0 ≤ 𝑐 ≤ 231, where 231 = −231 according to modular
 arithmetic. Hexadecimal constants must fit into 32 bits.
 The left << and right >> shift operations are arithmetic shifts. Since our numbers are signed, this means the right
 shift will copy the sign bit in the highest bit rather than filling with zero. Left shifts always fill the lowest bit with zero.
 Also, the shift quantity 𝑘 will be masked to 5 bits and is interpreted positively so that a shift is always between 0
 and 31, inclusively. This is also the hardware behavior of the appropriate arithmetic shift instructions on the x8664
 architecture and is consistent with C where the behavior is underspecified.
 The comparison operators <, <=, >, and >=, have their standard meaning on signed integers as in the definition of C.
 Operators == and != are overloaded operators that test for the equality of either a pair of ints or a pair of bools.
 Project Requirements
 For this project, you are required to hand in a complete working compiler for L2 that produces correct and executable
 target programs for x8664 Linux machines.
 Your compiler and test programs must be formatted and handed in via crow as specified below. For this lab, you must
 also write and hand in at least ten test programs. Please refer to What to Turn in (p. 9) for details.
 Repository setup
 You should have a working setup already from Lab 1. If not, refer to Lab 1 for more details on how to set up your
 source code repository.
 Test Files
 Tests are still handled by crow, for detailed instructions on how the testing system works, take a look at Lab 1. Test
 modifiers available for L2 tests are listed in Table 2.
 Runtime Environment
 crow uses a docker container when executing your project. Currently, it is based on the latest archlinux version with
 common development software such as gcc, make, java and ghc installed. If you use any other language and find that
 crow can not compile it yet, please report what software you are missing in the Build problems thread in the crow
 forum. Please also report any other problems you encounter that might need assistance in that forum 🐞.
 8
Modifier
 Argument
 Argument string short string
 Argument file
 Program input
 Program output
 Should succeed
 Should fail
 Should fail
 Should crash
 long string
 long string
 long string
 Parsing
 Semantic analysis
 Description
 An argument to the compiler, such as --compile
 The text you enter is written to a file and the file name passed to
 the compiler. Passing an input c file to the compiler is done using
 this modifier.
 Passes input on the standard input stream (“stdin”) to the binary.
 This is used to mimic user interactions.
 The output of the binary must match the given string.
 The compiler or your binary exits with exit code 0.
 The compiler should fail while lexing/parsing the input program.
 The semantic analysis phase of your compiler should fail on the
 input.
 Floating point exception Your binary crashes with signal SIGFPE.
 Should crash
 Exit code
 Exit codes
 Segmentation fault
 0-255
 Your binary crashes with signal SIGSEGV.
 Your binary exits with the given exit code, i.e. returns this value
 from main.
 Table 2: The currently implemented test modifiers in crow.
 Tests in crow typically assume your compiler exits successfully. But what does this actually mean and what does a
 failing invocation look like? crow uses the program exit code to determine success. To help us all write sensible tests,
 crow ships with a few preset exit codes imbued with meaning, which are available in the test creation page or the
 markdown test files.
 For your compiler the following applies:
 • exit code 0 indicates success
 Should succeed in crow
 • exit code 42 indicates that the code was rejected by your lexer or parser
 Should fail > Parsing in crow
 • exit code 7 indicates that the code was rejected by your semantic analysis
 Should fail > Semantic analysis in crow
 • any other exit code indicates a general unexpected failure
 For your binary the following applies:
 • exit code 0 indicates success
 Should succeed in crow
 • killed by signal
 ‣ SIGFPE indicates a division by zero
 Should crash > Floating point exception in crow
 ‣ SIGSEGV indicates a null pointer dereference. This is not yet relevant for you.
 Should crash > Segmentation fault in crow
 • any other exit code indicates a nonzero return value from the binary’s main function
 Exit code > [code]

 Notes and Hints
 The following section contains some additional hints that might be of use while you upgrade your compiler to accept
 L2 programs.
 Static Checking
 The specification of static checking should be implemented on abstract syntax trees, translating the rules into code.
 You should take some care to produce useful error messages.
 It may be tempting to wait until liveness analysis on abstract assembly to see if any variables are live at the
 beginning of the program and signal an error then, rather than checking this directly on the abstract syntax tree.
 There are two reasons to avoid this: (1) it may be difficult or impossible to generate decent error messages, and (2)
 the intermediate representation might undergo some transformations (for example optimizations, or transforming
 logical operators into conditionals) which make it difficult to be sure that the check strictly conforms to the given
 specification.
 Shift Operators
 There are some tricky details on the machine instructions implementing shift operators. The instruction sall k, D
 (shift arithmetic left long) and sarl k, D (shift arithmetic right long) take a shift value k and a destination operand D.
 The shift either has to he the %cl register, which consists of the lowest 8 bits of %rcx, or can be given as an immediate
 of at most 8 bits. In either case, only the low 5 bits affect the shift of a 32 bit value; the other bits are masked out.
 The assembler will fail if an immediate of more than 8 bits is provided as an argument.