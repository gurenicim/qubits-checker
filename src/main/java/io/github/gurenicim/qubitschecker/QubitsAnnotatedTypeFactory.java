package io.github.gurenicim.qubitschecker;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;

import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import io.github.gurenicim.qubitschecker.qual.Qubits;

/**
 * Infers the highest qubit index touched by {@code Gate} constructor calls (e.g. {@code new
 * X(2)}), propagates the max of that up through {@code new Step(gate...)} and (flow-sensitively,
 * via {@link QubitsTransfer}) through {@code step.addGate(...)} calls, and tags a {@code new
 * Program(n, ...)} expression itself with the highest valid index it allows ({@code n - 1}).
 * {@link #checkProgramConstruction} and {@link #checkAddStep} — called from {@link
 * QubitsVisitor} — compare those two numbers at the two places they can ever meet: gates passed
 * straight into {@code Program}'s constructor, and {@code program.addStep(step)}/{@code
 * addSteps(...)} calls.
 *
 * <p>Nothing here is ever a symbolic/declared annotation written by a developer — {@code
 * Program}/{@code Step}/{@code Gate} live in the external {@code strange} jar, so this factory
 * works purely by recognizing constructor/method shapes on the AST rather than by reading
 * annotations off a declared signature.
 */
public class QubitsAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    private static final Set<String> SINGLE_QUBIT_GATES =
            Set.of("X", "Y", "Z", "Hadamard", "Identity", "Measurement", "Oracle", "ProbabilitiesGate");
    private static final Set<String> TWO_QUBIT_GATES = Set.of("Cnot", "Cz", "Swap");
    private static final Set<String> THREE_QUBIT_GATES = Set.of("Toffoli");
    private static final String GATE_INTERFACE = "org.redfx.strange.Gate";
    private static final String STEP_CLASS = "Step";
    private static final String PROGRAM_CLASS = "Program";

    private final ExecutableElement valueElement;

    public QubitsAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        valueElement = TreeUtils.getMethod(Qubits.class, "value", 0, processingEnv);
        this.postInit();
    }

    String value(AnnotationMirror qubits) {
        return AnnotationUtils.getElementValue(qubits, valueElement, String.class);
    }

    boolean isQubits(AnnotationMirror a) {
        return AnnotationUtils.areSameByName(a, Qubits.class.getCanonicalName());
    }

    AnnotationMirror createQubits(String value) {
        AnnotationBuilder builder = new AnnotationBuilder(processingEnv, Qubits.class);
        builder.setValue("value", value);
        return builder.build();
    }

    /** The top annotation of this type system — "nothing further is known". */
    AnnotationMirror unknownQubits() {
        return getQualifierHierarchy().getTopAnnotations().iterator().next();
    }

    /** The constructor's declaring class' simple name, e.g. {@code "X"} for {@code new X(0)}. */
    private String constructedSimpleName(NewClassTree tree) {
        ExecutableElement constructor = TreeUtils.elementFromUse(tree);
        if (constructor == null) {
            return null;
        }
        return constructor.getEnclosingElement().getSimpleName().toString();
    }

    /** The value of {@code expr} if it is a compile-time integer literal, else {@code null}. */
    private static Integer literalIntValue(ExpressionTree expr) {
        if (expr.getKind() == Tree.Kind.INT_LITERAL) {
            return (Integer) ((LiteralTree) expr).getValue();
        }
        return null;
    }

    /**
     * Tags {@code type} with {@code @Qubits(max)} where {@code max} is the highest of exactly
     * {@code expectedArgs} literal-int constructor arguments — e.g. {@code new Cnot(0, 1)} with
     * {@code expectedArgs=2} tags {@code @Qubits("1")}. Leaves {@code type} untouched (defaulting
     * to {@code @UnknownQubits}) if the arg count doesn't match (e.g. {@code Cz()}'s no-arg
     * overload, or {@code Oracle(matrix)}) or any argument isn't a literal.
     */
    private void annotateFromLiteralIndices(NewClassTree tree, AnnotatedTypeMirror type, int expectedArgs) {
        List<? extends ExpressionTree> args = tree.getArguments();
        if (args.size() != expectedArgs) {
            return;
        }
        int max = -1;
        for (ExpressionTree arg : args) {
            Integer literal = literalIntValue(arg);
            if (literal == null) {
                return;
            }
            max = Math.max(max, literal);
        }
        type.replaceAnnotation(createQubits(String.valueOf(max)));
    }

    /**
     * Tags a {@code new Program(nQubits, ...)} call with {@code @Qubits(nQubits - 1)} — the
     * highest qubit index it's valid to use against this program — when {@code nQubits} is a
     * literal. This is a one-time fact fixed at construction (a Program's qubit count never
     * changes), so ordinary flow-sensitive tracking (built into the framework, no custom code
     * needed here) is enough to remember it for the variable's lifetime — unlike {@code Step},
     * {@code Program} doesn't need a {@link QubitsTransfer} case of its own.
     */
    private void annotateProgram(NewClassTree tree, AnnotatedTypeMirror type) {
        List<? extends ExpressionTree> args = tree.getArguments();
        if (args.isEmpty()) {
            return;
        }
        Integer declared = literalIntValue(args.get(0));
        if (declared == null) {
            return;
        }
        type.replaceAnnotation(createQubits(String.valueOf(declared - 1)));
    }

    /**
     * Tags a {@code new Step(...)} call with {@code @Qubits(max)}, the max over every argument
     * that is itself a {@code Gate} with a known {@code @Qubits}. Non-{@code Gate} arguments
     * (the optional leading {@code String name}) are ignored. If any {@code Gate} argument's
     * index isn't statically known, the whole step is left unknown too — better to miss a check
     * than to silently ignore one gate and wrongly clear the rest.
     */
    private void annotateStep(NewClassTree tree, AnnotatedTypeMirror type) {
        TypeElement gateElement = elements.getTypeElement(GATE_INTERFACE);
        if (gateElement == null) {
            return;
        }
        TypeMirror gateType = types.erasure(gateElement.asType());
        int max = -1;
        for (ExpressionTree arg : tree.getArguments()) {
            TypeMirror argType = types.erasure(TreeUtils.typeOf(arg));
            if (!types.isAssignable(argType, gateType)) {
                continue;
            }
            AnnotationMirror argQubits = getAnnotatedType(arg).getPrimaryAnnotation(Qubits.class);
            if (argQubits == null) {
                return;
            }
            max = Math.max(max, Integer.parseInt(value(argQubits)));
        }
        if (max >= 0) {
            type.replaceAnnotation(createQubits(String.valueOf(max)));
        }
    }

    /** Thrown by {@link #checkProgramConstruction} when a used qubit index is out of bounds. */
    static final class QubitOutOfBounds extends RuntimeException {
        final int declaredQubits;
        final int usedIndex;

        QubitOutOfBounds(int declaredQubits, int usedIndex) {
            super("qubit index " + usedIndex + " is out of bounds for a Program declared with "
                    + declaredQubits + " qubits");
            this.declaredQubits = declaredQubits;
            this.usedIndex = usedIndex;
        }
    }

    /**
     * Checks a {@code new Program(nQubits, steps...)} call: if {@code nQubits} is a literal,
     * compares it against every step argument's inferred {@code @Qubits} (skipping any step
     * whose requirement isn't statically known — nothing to compare it against).
     *
     * @throws QubitOutOfBounds if some step uses a qubit index {@code >= nQubits}
     */
    void checkProgramConstruction(NewClassTree tree) {
        List<? extends ExpressionTree> args = tree.getArguments();
        if (args.isEmpty()) {
            return;
        }
        Integer declared = literalIntValue(args.get(0));
        if (declared == null) {
            return;
        }
        for (int i = 1; i < args.size(); i++) {
            AnnotationMirror stepQubits = getAnnotatedType(args.get(i)).getPrimaryAnnotation(Qubits.class);
            if (stepQubits == null) {
                continue;
            }
            int usedIndex = Integer.parseInt(value(stepQubits));
            if (usedIndex >= declared) {
                throw new QubitOutOfBounds(declared, usedIndex);
            }
        }
    }

    /**
     * Checks a {@code program.addStep(step)} or {@code program.addSteps(step1, step2, ...)}
     * call: if {@code program}'s inferred {@code @Qubits} (its highest valid index, tagged at
     * construction — see {@link QubitsTreeAnnotator#visitNewClass}) is known, compares it against
     * each {@code Step} argument's inferred {@code @Qubits} (its highest used index, tracked
     * flow-sensitively by {@link QubitsTransfer} across {@code addGate} calls).
     *
     * @throws QubitOutOfBounds if some step uses a qubit index higher than the program allows
     */
    void checkAddStep(ExpressionTree receiver, List<? extends ExpressionTree> stepArgs) {
        AnnotationMirror programQubits = getAnnotatedType(receiver).getPrimaryAnnotation(Qubits.class);
        if (programQubits == null) {
            return;
        }
        int maxValidIndex = Integer.parseInt(value(programQubits));
        for (ExpressionTree stepArg : stepArgs) {
            AnnotationMirror stepQubits = getAnnotatedType(stepArg).getPrimaryAnnotation(Qubits.class);
            if (stepQubits == null) {
                continue;
            }
            int usedIndex = Integer.parseInt(value(stepQubits));
            if (usedIndex > maxValidIndex) {
                throw new QubitOutOfBounds(maxValidIndex + 1, usedIndex);
            }
        }
    }

    @Override
    protected QualifierHierarchy createQualifierHierarchy() {
        return new QubitsQualifierHierarchy(getSupportedTypeQualifiers(), elements, this);
    }

    @Override
    protected TreeAnnotator createTreeAnnotator() {
        return new ListTreeAnnotator(super.createTreeAnnotator(), new QubitsTreeAnnotator(this));
    }

    private class QubitsTreeAnnotator extends TreeAnnotator {

        QubitsTreeAnnotator(AnnotatedTypeFactory atypeFactory) {
            super(atypeFactory);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, AnnotatedTypeMirror type) {
            String name = constructedSimpleName(tree);
            if (name != null) {
                if (SINGLE_QUBIT_GATES.contains(name)) {
                    annotateFromLiteralIndices(tree, type, 1);
                } else if (TWO_QUBIT_GATES.contains(name)) {
                    annotateFromLiteralIndices(tree, type, 2);
                } else if (THREE_QUBIT_GATES.contains(name)) {
                    annotateFromLiteralIndices(tree, type, 3);
                } else if (STEP_CLASS.equals(name)) {
                    annotateStep(tree, type);
                } else if (PROGRAM_CLASS.equals(name)) {
                    annotateProgram(tree, type);
                }
            }
            return super.visitNewClass(tree, type);
        }
    }
}
