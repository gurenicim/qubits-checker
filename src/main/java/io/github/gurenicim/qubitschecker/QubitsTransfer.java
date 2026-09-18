package io.github.gurenicim.qubitschecker;

import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;

/**
 * Discovered by naming convention off {@link QubitsChecker} (see {@code
 * BaseTypeChecker.createFlowTransferFunction}): tracks {@code step.addGate(gate)} calls
 * flow-sensitively, so a {@code Step} local variable's inferred {@code @Qubits} keeps growing to
 * the max qubit index seen across every {@code addGate} call made on it — not just the gates
 * passed into its constructor.
 *
 * <p>Ordinary type inference (the {@code TreeAnnotator} in {@link QubitsAnnotatedTypeFactory})
 * only ever sees one expression at a time, so it cannot by itself account for "this same
 * variable was mutated three statements ago" — that requires the dataflow framework's
 * flow-sensitive store, which is what this class updates.
 *
 * <p><b>explanation:</b> {@code super.visitMethodInvocation} returns a {@code TransferResult} whose
 * {@code storeChanged} flag is {@code false} by default (it has no idea our custom mutation is
 * coming). If we just mutate {@code result.getRegularStore()} in place and return {@code result}
 * as-is, the analysis silently drops that mutation on the next node instead of propagating it —
 * it trusts the stale flag over the store's actual contents. Explicitly building a fresh {@link
 * RegularTransferResult} with {@code storeChanged=true} is what makes the update stick.
 */
public class QubitsTransfer extends CFTransfer {

    private final QubitsAnnotatedTypeFactory atypeFactory;

    public QubitsTransfer(CFAnalysis analysis) {
        super(analysis);
        atypeFactory = (QubitsAnnotatedTypeFactory) analysis.getTypeFactory();
    }

    @Override
    public TransferResult<CFValue, CFStore> visitMethodInvocation(
            MethodInvocationNode n, TransferInput<CFValue, CFStore> in) {
        TransferResult<CFValue, CFStore> result = super.visitMethodInvocation(n, in);

        ExecutableElement method = n.getTarget().getMethod();
        if (!"addGate".contentEquals(method.getSimpleName())
                || !"Step".contentEquals(method.getEnclosingElement().getSimpleName())) {
            return result;
        }

        Node receiver = n.getTarget().getReceiver();
        List<Node> args = n.getArguments();
        if (receiver == null || args.size() != 1) {
            return result;
        }
        JavaExpression receiverExpr = JavaExpression.fromNode(receiver);
        if (receiverExpr == null || !receiverExpr.isDeterministic(atypeFactory)) {
            return result;
        }

        CFStore store = result.getRegularStore();
        AnnotationMirror argQubits = qubitsOf(in.getValueOfSubNode(args.get(0)));

        AnnotationMirror newQubits;
        if (argQubits == null) {
            // This addGate's index isn't statically known -> the step's requirement as a whole
            // is no longer known either. Explicitly widen to top rather than leaving whatever
            // was there before (which would wrongly keep looking known).
            newQubits = atypeFactory.unknownQubits();
        } else {
            int argValue = Integer.parseInt(atypeFactory.value(argQubits));
            AnnotationMirror existingQubits = qubitsOf(store.getValue(receiverExpr));
            int combined = existingQubits == null
                    ? argValue
                    : Math.max(argValue, Integer.parseInt(atypeFactory.value(existingQubits)));
            newQubits = atypeFactory.createQubits(String.valueOf(combined));
        }
        // insertValue() alone would MERGE with whatever value is already there (taking "the
        // stronger of the new and old value... according to the lattice" per its own javadoc) —
        // and two different @Qubits values are incomparable in our hierarchy, so a plain
        // insertValue silently keeps the OLD value instead of recording the new max. clearValue
        // first so this is a genuine overwrite, not a merge.
        store.clearValue(receiverExpr);
        store.insertValue(receiverExpr, newQubits);

        return new RegularTransferResult<>(result.getResultValue(), store, true);
    }

    private AnnotationMirror qubitsOf(CFValue value) {
        if (value == null) {
            return null;
        }
        return value.getAnnotations().stream()
                .filter(a -> atypeFactory.isQubits(a))
                .findFirst()
                .orElse(null);
    }
}
