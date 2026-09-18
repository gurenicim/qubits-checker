package io.github.gurenicim.qubitschecker;

import java.lang.annotation.Annotation;
import java.util.Collection;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import org.checkerframework.framework.type.MostlyNoElementQualifierHierarchy;
import org.checkerframework.framework.util.QualifierKind;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypeSystemError;
import io.github.gurenicim.qubitschecker.qual.BottomQubits;
import io.github.gurenicim.qubitschecker.qual.Qubits;

/**
 * Two {@code @Qubits} annotations relate only through {@code @UnknownQubits}/{@code
 * @BottomQubits}: they are subtypes of each other only when their {@code value} is the exact
 * same string. Unlike the matrix checker's {@code @Dim}, there is no symbolic/declared side here
 * — every {@code @Qubits} this checker ever produces is already a concrete numeral, inferred by
 * {@link QubitsAnnotatedTypeFactory}.
 *
 * <p>{@code @BottomQubits} has no declared elements, but the LUB/GLB dispatch in {@code
 * MostlyNoElementQualifierHierarchy} routes to the {@code *WithElements} methods based on
 * whether the <em>combined</em> (LUB/GLB) kind has elements — and LUB(Qubits, BottomQubits) is
 * the Qubits kind, which does. So {@link #leastUpperBoundWithElements} and {@link
 * #greatestLowerBoundWithElements} both see {@code bottomKind} as a real possible operand (this
 * fires whenever the framework's dataflow merges two CFG paths — e.g. a normal path and an
 * exceptional-edge path — where one side never got a {@code @Qubits} value at all), even though
 * {@link #isSubtypeWithElements} never does (subtyping dispatch only looks at each operand's own
 * {@code hasElements()}, and {@code @BottomQubits} itself has none).
 */
final class QubitsQualifierHierarchy extends MostlyNoElementQualifierHierarchy {

    private final QualifierKind qubitsKind;
    private final QualifierKind bottomKind;
    private final ExecutableElement valueElement;

    QubitsQualifierHierarchy(
            Collection<Class<? extends Annotation>> qualifierClasses,
            Elements elements,
            QubitsAnnotatedTypeFactory factory) {
        super(qualifierClasses, elements, factory);
        qubitsKind = getQualifierKind(Qubits.class.getCanonicalName());
        bottomKind = getQualifierKind(BottomQubits.class.getCanonicalName());
        valueElement = TreeUtils.getMethod(Qubits.class, "value", 0, factory.getProcessingEnv());
    }

    private String value(AnnotationMirror qubits) {
        return AnnotationUtils.getElementValue(qubits, valueElement, String.class);
    }

    @Override
    protected boolean isSubtypeWithElements(
            AnnotationMirror subAnno,
            QualifierKind subKind,
            AnnotationMirror superAnno,
            QualifierKind superKind) {
        if (subKind == qubitsKind && superKind == qubitsKind) {
            return value(subAnno).equals(value(superAnno));
        }
        throw new TypeSystemError("Unexpected qualifiers: %s %s", subAnno, superAnno);
    }

    @Override
    protected AnnotationMirror leastUpperBoundWithElements(
            AnnotationMirror a1,
            QualifierKind qualifierKind1,
            AnnotationMirror a2,
            QualifierKind qualifierKind2,
            QualifierKind lubKind) {
        // Bottom is the identity for LUB: LUB(x, bottom) == x, whatever x is.
        if (qualifierKind1 == bottomKind) {
            return a2;
        }
        if (qualifierKind2 == bottomKind) {
            return a1;
        }
        if (qualifierKind1 == qubitsKind && qualifierKind2 == qubitsKind) {
            if (value(a1).equals(value(a2))) {
                return a1;
            }
            return getTopAnnotations().iterator().next();
        }
        throw new TypeSystemError("Unexpected qualifiers: %s %s", a1, a2);
    }

    @Override
    protected AnnotationMirror greatestLowerBoundWithElements(
            AnnotationMirror a1,
            QualifierKind qualifierKind1,
            AnnotationMirror a2,
            QualifierKind qualifierKind2,
            QualifierKind glbKind) {
        // Bottom is the absorbing element for GLB: GLB(x, bottom) == bottom, whatever x is.
        if (qualifierKind1 == bottomKind) {
            return a1;
        }
        if (qualifierKind2 == bottomKind) {
            return a2;
        }
        if (qualifierKind1 == qubitsKind && qualifierKind2 == qubitsKind) {
            if (value(a1).equals(value(a2))) {
                return a1;
            }
            return getBottomAnnotations().iterator().next();
        }
        throw new TypeSystemError("Unexpected qualifiers: %s %s", a1, a2);
    }
}
