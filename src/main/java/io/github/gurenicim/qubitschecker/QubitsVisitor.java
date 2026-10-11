package io.github.gurenicim.qubitschecker;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.javacutil.TreeUtils;
import io.github.gurenicim.qubitschecker.QubitsAnnotatedTypeFactory.QubitCollision;
import io.github.gurenicim.qubitschecker.QubitsAnnotatedTypeFactory.QubitOutOfBounds;

/**
 * Reports a compile-time error wherever a gate's qubit index can be shown to exceed a Program's
 * declared qubit count, or where two gates in the same step are shown to touch the same qubit
 * index — whether the gates were passed straight into {@code new Program(n, steps...)}/{@code
 * new Step(gate...)}, or added later via {@code program.addStep(step)}/{@code addSteps(...)}
 * and {@code step.addGate(...)} (see {@link QubitsTransfer} for how that mutating style is
 * tracked).
 *
 * <p>This checks specifically {@code Program}'s constructor and its {@code
 * addStep}/{@code addSteps} methods, and {@code Step}'s constructor and its {@code addGate}
 * method — the only places in this domain where "the declared bound"/"the indices already in
 * this step" and "the indices used against it" come together, so there is no generic
 * signature-driven trigger to hang this off of.
 */
public class QubitsVisitor extends BaseTypeVisitor<QubitsAnnotatedTypeFactory> {

    public QubitsVisitor(BaseTypeChecker checker) {
        super(checker);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void p) {
        ExecutableElement constructor = TreeUtils.elementFromUse(tree);
        if (constructor != null) {
            CharSequence className = constructor.getEnclosingElement().getSimpleName();
            if ("Program".contentEquals(className)) {
                report(tree, () -> atypeFactory.checkProgramConstruction(tree));
            } else if ("Step".contentEquals(className)) {
                report(tree, () -> atypeFactory.checkStepConstruction(tree));
            }
        }
        return super.visitNewClass(tree, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
        ExecutableElement method = TreeUtils.elementFromUse(tree);
        if (method != null) {
            CharSequence enclosingClass = method.getEnclosingElement().getSimpleName();
            if ("Program".contentEquals(enclosingClass)
                    && ("addStep".contentEquals(method.getSimpleName())
                            || "addSteps".contentEquals(method.getSimpleName()))) {
                ExpressionTree receiver = TreeUtils.getReceiverTree(tree);
                List<? extends ExpressionTree> stepArgs = tree.getArguments();
                if (receiver != null) {
                    report(tree, () -> atypeFactory.checkAddStep(receiver, stepArgs));
                }
            } else if ("Step".contentEquals(enclosingClass) && "addGate".contentEquals(method.getSimpleName())) {
                ExpressionTree receiver = TreeUtils.getReceiverTree(tree);
                List<? extends ExpressionTree> args = tree.getArguments();
                if (receiver != null && args.size() == 1) {
                    ExpressionTree gateArg = args.get(0);
                    report(tree, () -> atypeFactory.checkAddGate(receiver, gateArg));
                }
            }
        }
        return super.visitMethodInvocation(tree, p);
    }

    private void report(com.sun.source.tree.Tree tree, Runnable check) {
        try {
            check.run();
        } catch (QubitOutOfBounds e) {
            checker.reportError(
                    tree,
                    "program.qubit.outofbounds",
                    e.usedIndex,
                    e.declaredQubits,
                    e.declaredQubits - 1);
        } catch (QubitCollision e) {
            checker.reportError(tree, "program.qubit.collision", e.index);
        }
    }
}
