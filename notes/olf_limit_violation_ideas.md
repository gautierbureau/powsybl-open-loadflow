# Limit-violation detection in OLF — observations and suggested changes

Notes from reading `LimitViolationManager`, `AbstractLfBranch` and
`WoodburyDcSecurityAnalysis` while optimising the equivalent loop in a Python
port. Against `powsybl-open-loadflow` @ `fe0bd3df` (2026-07-09).

Nothing here is a correctness issue. All four items are about work the
post-contingency screen does per state that it does not need to.

---

## 0. What does *not* transfer

The change that motivated this read was batching the limit scan across
contingency states — computing the values and comparison masks once over a
`(states, branches)` block instead of once per state. That gave 12–23× in
NumPy, but the gain was **entirely** the interpreter's per-call floor (~5 µs
per NumPy call, against ~25 calls on 186-element arrays per state). The JVM
JITs OLF's scalar loop to machine code and pays no equivalent tax, so
restructuring `detectViolations` the same way would buy little and would break
the abstraction that lets one detector serve both AC and DC.

Worth stating explicitly because the rest of this document is the *residual*
after that trick — the part that turned out to be language-independent.

Also already handled in OLF, and worth noting so nobody proposes it: the
per-branch limit metadata is memoised. `AbstractLfBranch.getLimits1/2`
(`AbstractLfBranch.java:148`, `:181`) build the `LfLimitsGroup` list lazily on
first access, with the sort and the limit *reductions* already applied
(`createLimits` → `LfLimitsGroup.createSortedLimitsList`). Per state the
detector only re-reads them. That is the invariant-hoisting half, and it is
done.

---

## 1. The `LimitViolation` is built before it is known to be needed

**Where.** `LimitViolationManager.java:124`, `:136`, `:150` — each of
`detectBranchCurrentViolations` / `detectBranchActivePowerViolations` /
`detectBranchApparentPowerViolations` calls `createLimitViolation(...)`
(`:195`), which runs a `LimitViolationBuilder` and allocates a
`LimitViolation`. Only afterwards does `addLimitViolation` (`:95`) consult the
reference manager and possibly throw the object away:

```java
private void addLimitViolation(LimitViolation limitViolation, Pair<Object, String> key) {
    if (reference != null) {
        var referenceLimitViolation = reference.violations.get(key);
        if (referenceLimitViolation == null || !violationWeakenedOrEquivalent(...)) {
            violations.put(key, limitViolation);
        }
    }
    ...
}
```

**Why it matters.** In a security analysis the post-contingency manager is
always constructed with the pre-contingency one as `reference`
(`WoodburyDcSecurityAnalysis.java:211`), so the report is differential. On a
network that already has base-case violations, a contingency that does not
aggravate them still builds one object per violated branch-side and drops every
one of them. That is the common case, not the corner case.

**The fix.** `violationWeakenedOrEquivalent` (`:273`) reads only
`getLimitType()`, `getLimit()` and `getValue()` — all three are in hand before
the builder runs (`temporaryLimit.getValue() * scale`, `value * scale`, and the
`LimitViolationType` argument). So the comparison can be done on the raw
values, and only survivors get materialised. Concretely: an overload of
`addLimitViolation` that takes `(key, type, limit, value)` plus a
`Supplier<LimitViolation>`, or simply hoisting the reference lookup and the
predicate into the three `detectBranch*Violations` methods ahead of the
`createLimitViolation` call.

The same gap exists in our port and is on our list too — it is not an
OLF-specific mistake, it is what the "build then filter" shape naturally
produces.

---

## 2. The accumulator key allocates and hashes strings per violation

**Where.** `LimitViolationManager.java:44`:

```java
private final Map<Pair<Object, String>, LimitViolation> violations = new LinkedHashMap<>();
```

keyed via `addBranchLimitViolation` (`:106`) with

```java
Pair.of(getSubjectIdSide(limitViolation), limitViolation.getOperationalLimitsGroupId())
```

i.e. `Pair.of(Pair.of(String subjectId, ThreeSides side), String groupId)`.

**Cost per detected violation.** Two `Pair` allocations, a `String` hash of the
subject id and of the group id, a `HashMap` node allocation on `put`, and — in
the differential path — the same key construction again for the
`reference.violations.get(key)` lookup.

**Why an index would do.** Every post-contingency `LimitViolationManager` is
freshly constructed per contingency (`WoodburyDcSecurityAnalysis.java:211`) and
holds exactly one state. The subject is always an `LfBranch` / `LfBus` /
`LfVoltageAngleLimit` that already carries a dense `getNum()`. A key of
`branchNum * 2 + sideOrdinal` (with the operational-limits-group index folded
in, since the groups are enumerated per branch anyway) is an `int`, and the
accumulator can be an array or an `Int2ObjectLinkedOpenHashMap`. `getSubjectId`
is then needed only when the violation is finally emitted.

This composes with item 1: if the filter runs before materialisation, the
reference lookup is on the int key and never touches a string.

---

## 3. Post-contingency flows are re-derived through the `Evaluable` graph

**Where.** `WoodburyDcSecurityAnalysis.java:198`:

```java
loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);
updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyStates);
```

then, at `:212`, `detectViolations` pulls `branch.getP1().eval()` /
`getI1().eval()` per branch per side (`LimitViolationManager.java:121`, `:133`,
`:146`), walking `Evaluable` → `ClosedBranchSide1DcFlowEquationTerm.eval` →
`Variable.getRow()` → the state vector.

**Why it is avoidable here specifically.** The Woodbury engine has already
produced the complete post-contingency `double[] θ`. The DC branch flow is
`-power * (θ2 - θ1 + A2 - a1)` — one multiply-add per branch off two array
reads. Filling a `double[] p1` (and `i1`, which is a scale of it) in a single
flat pass and handing the detector a primitive view would replace the whole
traversal.

**What the win actually is.** Not dispatch amortisation — the JIT handles
scalar loops. It is that `Evaluable.eval()` at that call site is megamorphic
(open/closed × side 1/side 2 × AC/DC branch terms all flow through it), so it
cannot be inlined, and each call then chases `Variable` objects to reach the
state array. A flat loop over primitives is inlinable, vectorisable and
cache-friendly. This is the closest analogue to the batching change that is
worth doing in Java, because it removes indirection rather than interpreter
overhead.

**Caveat.** The `Evaluable` indirection is what lets one `LimitViolationManager`
serve AC and DC. So this is a *DC fast-path* suggestion — e.g. an optional
`detectViolations(LfNetwork, Predicate, FlowSnapshot)` overload that the
Woodbury path fills, falling back to the current evaluation when absent — not a
proposal to remove the abstraction.

---

## 4. Stream pipelines allocated per state

**Where.** `LimitViolationManager.java:80`, `:83`, `:86`:

```java
network.getBranches().stream().filter(b -> !isBranchDisabled.test(b)).forEach(this::detectBranchViolations);
network.getBuses().stream().filter(b -> !b.isDisabled()).forEach(this::detectBusViolations);
network.getVoltageAngleLimits().stream().filter(...).forEach(...);
```

Three pipelines constructed per contingency. Plain `for` loops over the backing
lists are a mechanical rewrite. Small next to items 1–3 — listed for
completeness, and because the escape analysis that would elide these is not
guaranteed.

---

## Suggested order

1. **Item 1** — biggest effect on a violating screen, smallest diff, no API
   change. Purely internal to `LimitViolationManager`.
2. **Item 2** — pairs naturally with item 1 (both touch the same three
   methods), and only makes sense once the filter has moved ahead of the
   builder.
3. **Item 3** — largest effect on an *all-clear* screen (where items 1–2 do
   nothing, since no records are built), but needs an API seam between the
   Woodbury engine and the detector.
4. **Item 4** — free, do it whenever those methods are being touched anyway.

Items 1 and 2 are invisible when nothing violates; item 3 is invisible when
everything does. Between them they cover both regimes, which is why the order
above is by diff size rather than by measured gain.

## What is not measured

These are code-reading observations. No JMH numbers back them — I profiled the
equivalent loop in Python, where the cost structure is different enough
(item 0) that the ratios do not carry over. Items 1–2 should be measured on a
screen that violates densely, item 3 on one that does not.
