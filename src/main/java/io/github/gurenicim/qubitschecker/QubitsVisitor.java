package io.github.gurenicim.qubitschecker;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.javacutil.TreeUtils;
import io.github.gurenicim.qubitschecker.QubitsAnnotatedTypeFactory.QubitOutOfBounds;

/**
 * Reports a compile-time error wherever a gate's qubit index can be shown to exceed a Program's
 * declared qubit count — whether the gates were passed straight into {@code new Program(n,
 * steps...)}, or added later via {@code program.addStep(step)}/{@code addSteps(...)} after
 * building the step up with {@code step.addGate(...)} calls (see {@link QubitsTransfer} for how
 * that second style is tracked).
 *
 * <p>This checks specifically for {@code Program}'s constructor and its {@code
 * addStep}/{@code addSteps} methods — there is only one place in this domain where "the declared
 * bound" and "the indices used against it" come together, so there is no generic
 * signature-driven trigger to hang this off of.
 */
public class QubitsVisitor extends BaseTypeVisitor<QubitsAnnotatedTypeFactory> {

    public QubitsVisitor(BaseTypeChecker checker) {
        super(checker);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void p) {
        ExecutableElement constructor = TreeUtils.elementFromUse(tree);
        if (constructor != null && "Program".contentEquals(constructor.getEnclosingElement().getSimpleName())) {
            report(tree, () -> atypeFactory.checkProgramConstruction(tree));
        }
        return super.visitNewClass(tree, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
        ExecutableElement method = TreeUtils.elementFromUse(tree);
        if (method != null
                && "Program".contentEquals(method.getEnclosingElement().getSimpleName())
                && ("addStep".contentEquals(method.getSimpleName())
                        || "addSteps".contentEquals(method.getSimpleName()))) {
            ExpressionTree receiver = TreeUtils.getReceiverTree(tree);
            List<? extends ExpressionTree> stepArgs = tree.getArguments();
            if (receiver != null) {
                report(tree, () -> atypeFactory.checkAddStep(receiver, stepArgs));
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
        }
    }
}
