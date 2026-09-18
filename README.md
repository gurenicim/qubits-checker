# qubits-checker

A Checker Framework plugin that catches out-of-range qubit indices in
[Strange](https://github.com/redfx-quantum/strange) quantum programs at **compile time**,
instead of failing at runtime (or silently doing the wrong thing).

## The problem

```java
Program program = new Program(2);      // declares 2 qubits: indices 0 and 1
Step step = new Step(new X(5));         // typo — qubit 5 doesn't exist
program.addStep(step);
```

Nothing about `new X(5)` is wrong in isolation — it's only wrong *relative to* a 2-qubit
`Program`. This checker looks at both sides together: it infers the highest qubit index every
gate touches, tracks that through `Step` construction (including the mutating
`step.addGate(...)` style used across most real code), and compares it against the `Program`
it ends up in.

## What it checks

For every `new Program(nQubits, steps...)` call where `nQubits` is a literal:

1. Infer the highest qubit index each `Gate` constructor argument touches (`new X(2)` → `2`,
   `new Cnot(0, 1)` → `1`, `new Toffoli(0, 1, 3)` → `3`), when those arguments are themselves
   int literals.
2. Infer a `Step`'s requirement as the max over the gates passed to its constructor, or added
   later via `addGate(...)`.
3. Compare each step's requirement against `nQubits`. If any used index is `>= nQubits`,
   report a compile error pointing at the exact call site.

Gates recognized: `X`, `Y`, `Z`, `Hadamard`, `Identity`, `Measurement`, `Oracle(int)`,
`ProbabilitiesGate` (single qubit index), `Cnot`, `Cz`, `Swap` (two indices), `Toffoli`
(three indices).

## Install

Via [JitPack](https://jitpack.io/#gurenicim/qubits-checker) (no account, no publishing
step on your end — it builds straight from this repo's tags):

```groovy
plugins {
    id 'org.checkerframework' version '1.0.2'
}

repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'org.redfx:strange:0.1.0'

    compileOnly 'com.github.gurenicim:qubits-checker:v1.0.1'
    checkerFramework 'com.github.gurenicim:qubits-checker:v1.0.1'
    checkerFramework 'org.checkerframework:checker:3.49.5'
}

checkerFramework {
    version = '3.49.5'
    checkers = ['io.github.gurenicim.qubitschecker.QubitsChecker']
}
```

(The Gradle coordinates use JitPack's `com.github.<user>` group; the checker's own Java
package, used in the `checkers` list above, is unaffected and stays
`io.github.gurenicim.qubitschecker`.)

Then `./gradlew compileJava` fails the build with something like:

```
error: [program.qubit.outofbounds] Qubit index 2 is out of bounds: this Program declares 2
qubits, so valid indices are 0..1.
```

wherever a gate's index doesn't fit the `Program` it's used with.

## Known limitation

Only qubit indices that are compile-time literals are checked. A dynamically computed index
(a loop variable, a method parameter) is left unchecked rather than guessed at.

## How it works

See [`docs/CHECKER_EXPLANATION.md`](docs/CHECKER_EXPLANATION.md) for the full mechanism —
tree visitors, the dataflow transfer function that tracks `addGate` across statements, and
two sharp edges in the Checker Framework's dataflow API that aren't obvious going in.

## Building locally

```bash
./gradlew build
./gradlew publishToMavenLocal
```

## License

MIT
