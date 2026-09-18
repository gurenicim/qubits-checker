# How the checker works

This walks through how the plugin in [`src/`](../src) catches an out-of-range qubit index
at **compile time**, in any project that builds a
[Strange](https://github.com/redfx-quantum/strange) `Program`/`Step`/`Gate`.

## 1. The vocabulary: three qualifiers

| Qualifier | Meaning |
|---|---|
| `@UnknownQubits` | "The highest qubit index this expression touches isn't statically known." Default for everything. |
| `@Qubits(value=N)` | "This expression is known to touch qubit index `N` and no higher." |
| `@BottomQubits` | Only used for `null`. |

`@Qubits` never appears as a *declared* annotation written by a developer — `Program`/`Step`/
`Gate` are classes from the `strange` jar, not source this checker owns, so there's nothing
to annotate. Every `@Qubits` this checker ever produces is inferred purely from the shape of
the code.

## 2. Where each `@Qubits` comes from

`QubitsAnnotatedTypeFactory`'s `TreeAnnotator` overrides `visitNewClass` — fired for every
`new Foo(...)` expression in the file — and recognizes three shapes:

- **A gate constructor** (`new X(0)`, `new Cnot(0, 1)`, `new Toffoli(0, 1, 3)`): if every
  constructor argument is an integer literal, tags the expression `@Qubits(max-of-the-args)`.
- **`new Step(gate...)`**: tags the expression `@Qubits(max)` over every constructor argument
  that is itself a `Gate` with a known `@Qubits`. If any gate argument's index isn't statically
  known, the whole step is left `@UnknownQubits` too, rather than silently ignoring that one gate.
- **Anything else**: left as the default, `@UnknownQubits`.

## 3. Where the check happens

`QubitsVisitor.visitNewClass` fires for every `new Foo(...)` too — but only acts when `Foo` is
`Program`. It calls `QubitsAnnotatedTypeFactory.checkProgramConstruction`, which:

1. Reads the first constructor argument (`nQubits`) as a literal. If it isn't one, gives up —
   nothing to check against.
2. For each remaining `Step` argument, reads its inferred `@Qubits`. If unknown, skips it
   (nothing to compare).
3. If a step's used index is `>= nQubits`, throws `QubitOutOfBounds`.

The visitor catches that exception and calls
`checker.reportError(tree, "program.qubit.outofbounds", ...)`, which looks up the template in
[`messages.properties`](../src/main/resources/io/github/gurenicim/qubitschecker/messages.properties)
and attaches a compiler error to the `new Program(...)` call's exact source line. If any error
is reported anywhere in the file, `javac` fails the build and never emits a `.class` file.

## 4. A full trace

```java
Step badStep = new Step(new X(2));
Program badProgram = new Program(2, badStep);
```

- `new X(2)`: `visitNewClass` sees class `X`, one literal arg `2` → tags `@Qubits("2")`.
- `new Step(new X(2))`: sees class `Step`, one `Gate`-typed argument with `@Qubits("2")`
  → tags the whole `new Step(...)` expression `@Qubits("2")`. The framework's flow analysis
  now remembers `badStep` is `@Qubits("2")` at every later use — no extra code needed for
  that part.
- `new Program(2, badStep)`: `QubitsVisitor.visitNewClass` sees the constructor is `Program`'s,
  calls `checkProgramConstruction`. First arg `2` is a literal → `declared = 2`. Second arg
  `badStep` has `@Qubits("2")` → `usedIndex = 2`. `2 >= 2` → throws `QubitOutOfBounds(2, 2)`.
- Visitor catches it, reports:
  `error: [program.qubit.outofbounds] Qubit index 2 is out of bounds: this Program declares 2
  qubits, so valid indices are 0..1.`

## 5. Tracking the mutating style: `QubitsTransfer`

`new Step(new X(0))` is one expression — the `TreeAnnotator` sees everything it needs in one
shot. But most real code looks like:

```java
Step step = new Step();
step.addGate(new X(0));
step.addGate(new Cnot(0, 1));
program.addStep(step);
```

`step`'s requirement grows across three separate statements. A `TreeAnnotator` only ever sees
one expression at a time, so it structurally cannot express "this variable was mutated two
statements ago" — that needs the dataflow framework's **flow-sensitive store**, which threads
a `CFStore` through the control-flow graph, one node at a time — the same mechanism nullness
checkers use to remember `x != null` after an `if` check.

`QubitsTransfer` is discovered automatically — the Checker Framework reflectively looks for a
class named `<X>Transfer` next to `<X>Checker`, the same way it finds `<X>Visitor` and
`<X>AnnotatedTypeFactory`. Its `visitMethodInvocation` fires for every method call node in the
CFG; it filters for `Step.addGate(gate)` calls and, for those, updates the store entry for the
receiver (`step`) to the max of its current tracked value and the new gate's index.

`QubitsVisitor` also checks `program.addStep(step)`/`addSteps(...)` calls directly (via
`checkAddStep`), comparing the receiver `Program`'s tagged bound against each `Step` argument's
tracked `@Qubits`.

### Two gotchas that cost real debugging time

**`result.getRegularStore()` isn't safe to mutate in place and return as-is.**
`super.visitMethodInvocation()` returns a `TransferResult` whose `storeChanged` flag defaults
to `false`. If you just call `store.insertValue(...)` on that returned store and `return
result;`, the analysis engine trusts the stale flag over the store's actual contents and drops
the mutation. The fix is to build a fresh `RegularTransferResult` with `storeChanged=true`
explicitly.

**`store.insertValue(expr, newAnnotation)` merges, it does not overwrite.** Two different
`@Qubits` values are incomparable in this hierarchy, so "the stronger of the new and old
value" resolves to keeping the *old* one — a later `addGate`'s higher index never wins. The
fix is `store.clearValue(expr)` immediately before `insertValue`.

## 6. Wiring it into a project

Any project that depends on `org.redfx:strange` can apply this as an annotation processor
via the [Checker Framework Gradle plugin](https://checkerframework.org/manual/#gradle):

```groovy
plugins {
    id 'org.checkerframework' version '1.0.2'
}

repositories {
    mavenCentral()
    // or: maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'org.redfx:strange:0.1.0'

    compileOnly 'io.github.gurenicim:qubits-checker:1.0.0'
    checkerFramework 'io.github.gurenicim:qubits-checker:1.0.0'
    checkerFramework 'org.checkerframework:checker:3.49.5'
}

checkerFramework {
    version = '3.49.5'
    checkers = ['io.github.gurenicim.qubitschecker.QubitsChecker']
}
```

From then on, `./gradlew compileJava` fails with a compiler error at the exact
`new Program(...)`/`addStep(...)` call site whenever a gate's qubit index would be out of
bounds — instead of that mistake surfacing later as a runtime exception (or silently wrong
output) when the program actually runs.

## 7. Known limitation

Only qubit indices that are compile-time integer literals are checked. An index computed at
runtime (a loop variable, a method parameter) is left unchecked rather than guessed at.

## 8. Files at a glance

| File | Role |
|---|---|
| `qual/Qubits.java`, `UnknownQubits.java`, `BottomQubits.java` | The three qualifiers. |
| `QubitsQualifierHierarchy.java` | Defines subtyping: two `@Qubits` relate only via exact `value` match. |
| `QubitsAnnotatedTypeFactory.java` | Infers `@Qubits` from gate/step/program constructor shapes; exposes `checkProgramConstruction` and `checkAddStep`. |
| `QubitsTransfer.java` | Flow-sensitively tracks `step.addGate(...)` calls across statements. |
| `QubitsVisitor.java` | Calls the two check methods at `Program` constructors and `addStep`/`addSteps` calls; reports the compile error. |
| `QubitsChecker.java` | Empty entry point, wired by naming convention. |
| `messages.properties` | The error message template. |
