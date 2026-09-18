package io.github.gurenicim.qubitschecker;

import org.checkerframework.common.basetype.BaseTypeChecker;

/**
 * Statically verifies that every qubit index used by a {@code Gate} inside a {@code Step} passed
 * to {@code new Program(n, steps...)} is within bounds for the declared qubit count {@code n}.
 */
public class QubitsChecker extends BaseTypeChecker {
}
