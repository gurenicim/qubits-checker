package io.github.gurenicim.qubitschecker.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * The highest qubit index an expression is known to touch.
 *
 * <p>{@code value} is always a concrete numeral (e.g. {@code "2"}). It is never written by hand
 * in source — it is inferred by {@code QubitsAnnotatedTypeFactory} from literal constructor
 * arguments to {@code Gate} subclasses (e.g. {@code new X(2)} infers {@code @Qubits("2")}),
 * propagated through {@code new Step(gate...)} as the max over its gates, and compared against
 * the declared qubit count at each {@code new Program(n, steps...)} call.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
@SubtypeOf(UnknownQubits.class)
public @interface Qubits {
    String value();
}
