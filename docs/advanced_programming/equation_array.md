# Equation arrays and scalar equations

PowSyBl Open Load Flow solves every problem (AC, DC, sensitivity, security) by
building a system of equations $\mathbf f(\mathbf x)=\mathbf t$ and driving the
mismatch to zero with Newton–Raphson (see the [Load flow modeling
reference](../loadflow/06_newton_raphson.md)). Internally that system has **two
interchangeable representations** of the very same linear algebra:

- a **scalar** representation — one Java object per equation and per term, and
- an **array** (vectorised) representation — one object holding *all* the
  equations of a given type, backed by primitive arrays.

Both produce exactly the same equation vector and Jacobian; the array form exists
only for performance. This page explains each representation and, most
importantly, how they coexist inside a single `EquationSystem`.

Everything here lives in the package `com.powsybl.openloadflow.equations`; the
vectorised AC bindings live in `com.powsybl.openloadflow.ac.equations.vector`.

## The shared vocabulary

Whatever the representation, the same three notions index the system
([`equations/Variable.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/Variable.java),
[`equations/EquationSystemIndex.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationSystemIndex.java)):

- A **variable** is a `(elementNum, type)` pair (for example the voltage magnitude
  of bus 7). Each variable owns one **row** of the Jacobian. A variable is given a
  row only while at least one active term depends on it.
- An **equation** is a `(elementNum, type)` pair (for example the active-power
  balance of bus 7). Each equation owns one **column**.
- The Jacobian is filled `matrix[row][column]` and assembled **column by column**
  (equation by equation), each entry placed at the row of the variable it
  differentiates against.

Equation and variable *types* are enums implementing `Quantity`, which ties a type
to a kind of `LfElement` (`BUS`, `BRANCH`, …). The whole framework is generic over
`<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>`.

## The scalar representation

The classic form. The `Equation` interface is implemented by
[`SingleEquation`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/SingleEquation.java):
**one object is one equation for one element**. It holds its `column`, an `active`
flag, and a `List<SingleEquationTerm>` whose sum *is* the equation. A term
([`EquationTerm`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationTerm.java) /
`SingleEquationTerm`) is an `Evaluable` that can compute its value `eval()` and its
partial derivatives `der(variable)`.

A bus balance equation is *built up* by adding the terms of the branches incident
to that bus. In [`AcEquationSystemCreator`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationSystemCreator.java)
the side-1 active-flow term of a branch is added into bus 1's `BUS_TARGET_P`
equation and the side-2 term into bus 2's — which is exactly how the off-diagonal
coupling of the network appears in the Jacobian:

```
BUS_TARGET_P(bus i) :  sum over incident branches b of  p_{b,i}(x)  =  target
```

Evaluating the system then means:

- **value**: `SingleEquation.eval()` sums `term.eval()` over active terms; the
  result is written at `array[column]` by
  [`EquationVector`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationVector.java);
- **derivatives**: `SingleEquation.der(handler)` walks its terms grouped by
  variable and, for each variable that currently has a row, calls
  `handler.onDer(variable, value, …)`, which
  [`JacobianMatrix`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/JacobianMatrix.java)
  turns into `matrix.add(variable.getRow(), column, value)`.

This representation is simple and flexible (terms support `multiply(...)`,
`minus()`, a right-hand side), but it allocates one object per equation **and** one
per term — on a large network, hundreds of thousands of small objects with virtual
`eval()`/`der()` calls.

## The array (vectorised) representation

[`EquationArray`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationArray.java)
is **one object that represents every equation of a given type at once** — for
instance a single `EquationArray` of type `BUS_TARGET_P` stands for the
active-power balance of *all* buses. Instead of a list of term objects it stores
flat primitive arrays:

- `elementCount`, a `boolean[] elementActive`, and the active `length`;
- the column mapping `firstColumn` + `elementNumToColumn[]` (active elements get a
  *contiguous* block of columns starting at `firstColumn`; inactive ones map to
  `-1`);
- a list of [`EquationTermArray`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationTermArray.java) —
  the vectorised term groups summed into these equations.

An `EquationTermArray` is, likewise, **one object holding the same kind of term for
all elements** (e.g. the side-1 active flow of every branch). It stores only the
*wiring* — for each term it records which **equation element** it contributes to
and which **term element** supplies the value (`addTerm(equationElementNum,
termElementNum)`), flattened by `compress()` into a CSR-style layout for fast
iteration. It does *not* store the maths: that is delegated to an **evaluator**.

### The evaluator pattern

`EquationTermArray.Evaluator` is the heart of the array form. One evaluator
computes a whole term kind across all elements:

```java
double[]   eval();                       // value for every term element
double     eval(int termElementNum);     // value for one element
double[][] evalDer();                    // evalDer()[localIndex][termElementNum]
List<Derivative<V>> getDerivatives(int termElementNum);
boolean    isDisabled(int termElementNum);
```

The AC evaluators
([`ac/equations/vector/`](https://github.com/powsybl/powsybl-open-loadflow/tree/main/src/main/java/com/powsybl/openloadflow/ac/equations/vector))
read a *struct-of-arrays* view of the network. For example
`ClosedBranchSide1ActiveFlowEquationTermArrayEvaluator.eval()` simply returns the
precomputed `branchVector.p1` array, and `evalDer()` returns
`{dp1dv1, dp1dv2, dp1dph1, dp1dph2, dp1da1, dp1dr1}`. The matching
`getDerivatives(branchNum)` returns `Derivative(V@bus1, 0)`, `Derivative(V@bus2, 1)`,
`Derivative(PHI@bus1, 2)`, … — each pairing a `Variable` with the **local index**
of its column in `evalDer()`. Variable look-ups are element-local: the evaluator
reads `branchVector.bus1Num[branchNum]` and asks the shared `VariableSet` for the
corresponding variable. The physics itself is computed in bulk into the branch
vector elsewhere (the network vector update), so the same numbers are never
recomputed per term object.

### Bulk evaluation

- **value** — `EquationArray.eval(double[] values)` fetches each term array's whole
  `evaluator.eval()` array **once**, then scatters the contributions into the right
  columns, skipping inactive elements and terms.
- **derivatives** — `EquationArray.der(handler)` is backed by a precomputed
  `EquationDerivativeVector`: for every (element, term, variable) entry it caches a
  live reference to the variable's row (a `MutableInt`) and to the relevant column
  of `evalDer()`. At Jacobian time it refreshes rows and values from those cached
  references and accumulates contributions that share a row, then calls
  `handler.onDer(column, row, value, …)`. Note the array handler passes
  **`int column, int row`** directly, whereas the scalar handler passes a
  `Variable` — same destination slot, different signature.

To callers that still expect a scalar `Equation`, `EquationArray.getElement(num)`
returns a lightweight **façade** object whose `getColumn()`, `isActive()`,
`eval()`, `addTerm(...)` simply delegate into the arrays — so an array equation can
be manipulated as if it were a `SingleEquation`.

## How the two representations interact

This is the part that surprises people: **both forms live in the same
`EquationSystem` at the same time.**

[`EquationSystem`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationSystem.java)
holds a map of scalar `SingleEquation`s **and** an `EnumMap<E, EquationArray>` — at
most **one array per equation type**. `createEquation(element, type)` checks the
array map first: if that *type* is vectorised it returns the array façade,
otherwise it creates a `SingleEquation`. So a given **type** is entirely scalar or
entirely array, but a single system freely mixes types — e.g. `BUS_TARGET_P` and
`BUS_TARGET_Q` as arrays while `BUS_TARGET_V`, the distribution equations, HVDC,
etc. stay scalar.

The two representations are stitched together by three shared mechanisms:

1. **Shared rows and columns** (`EquationSystemIndex`). Columns are numbered by
   assigning one to each sorted `SingleEquation` first, then giving each
   `EquationArray` a *contiguous block* of `length` columns. Rows are assigned to
   `Variable`s by reference counting over **both** scalar and array terms — so an
   array term and a scalar term that depend on the same variable write to the
   **same row**. The index reverse-maps any column back to a `SingleEquation` or to
   `equationArray.getElement(...)`.

2. **Shared assembly.** `EquationVector` first loops the scalar equations
   (`array[column] = evalLhs()`), then loops the arrays
   (`equationArray.eval(array)`). `JacobianMatrix` interleaves the two **by column
   index**: it emits scalar equations up to the next array's `firstColumn`, then
   the whole array block, and repeats. The resulting matrix is identical to what a
   purely scalar build would produce.

3. **A genuine bridge inside an array equation.** An `EquationArray` element can
   carry, in addition to its vectorised term arrays, ordinary
   `SingleEquationTerm`s attached to one specific element (the
   `singleTermsByEquationElementNum` channel). This is how a one-off, non-vectorised
   contribution is added to an otherwise vectorised equation; `EquationArray.eval`
   and `der` blend both. The reverse — pushing an array term into a plain
   `SingleEquation` — is **not** supported.

**Activation** differs in mechanism but not in effect: scalar equations and terms
flip a per-object `active` boolean; the array form flips a per-index flag
(`elementActive[]`, `termActive[]`) on shared objects, with the helper
`updateElementEquation(element, enabled)` toggling an element's equation and its
matching array terms together. Both ultimately notify the same
`EquationSystemIndex` and invalidate the same Jacobian.

## Choosing the representation

For AC, the choice is made by `AcLoadFlowParameters.isVectorized()` (default
`true`), read in `AcLoadFlowContext`:

- vectorised → [`AcVectorizedEquationSystemCreator`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/vector/AcVectorizedEquationSystemCreator.java),
- otherwise → `AcEquationSystemCreator` (purely scalar).

The vectorised creator *extends* the scalar one. It creates `EquationArray`s for
`BUS_TARGET_P`/`BUS_TARGET_Q` and four `EquationTermArray`s (closed branch side 1/2
× active/reactive), then calls `super.create(...)` so **all other equation types
are still built scalar by the parent**. The trick is that it overrides only the
four closed-branch flow-term factories to return an array element instead of a real
term object: the same wiring code runs, but registering the "term" calls
`EquationTermArray.addTerm(...)`. So in vectorised mode only the closed-branch
active/reactive flows are arrays; open branches, controls, distribution, voltage
targets, HVDC, etc. remain scalar. The asymmetrical (three-sequence) creator is a
separate path and is never vectorised.

The motivation is purely performance: the array form replaces a large object graph
and many virtual calls with a handful of `EquationTermArray`s over primitive
struct-of-arrays, evaluated in tight loops returning whole `double[]`/`double[][]`,
with derivative scatter through cached matrix-slot indices — far better cache
locality, far fewer allocations.

## Constraints and gotchas

- **One array per type.** `equationArrays` is an `EnumMap`; a type cannot have two
  arrays. The scalar/array mix is *per type*, not within a type.
- **No RHS, no scalar algebra in arrays.** A `SingleEquationTerm` with a
  right-hand side cannot be attached to an array equation, and the array term
  wrapper's `multiply(...)`/`minus()` throw `UnsupportedOperationException`.
  Vectorised terms must be plain.
- **Only closed-branch P/Q flows are vectorised today** (in the AC symmetric path).
  Everything else is scalar even when `vectorized` is on.
- **Same numbers either way.** Whichever representation is active, the equation
  vector and Jacobian are identical — so a plug-in that reads the solved state, a
  sensitivity, or a Jacobian does not need to care which one was used.

## Scalar vs array at a glance

| Aspect | Scalar (`SingleEquation` / `SingleEquationTerm`) | Array (`EquationArray` / `EquationTermArray`) |
|---|---|---|
| Granularity | one object **per element** (and per term) | one object **per type** (and per term kind) |
| Object count | O(#elements × #terms) | O(#types + #term kinds) |
| Per-element view | the object itself | façade from `EquationArray.getElement(num)` |
| Value | `eval()` sums term objects | `eval(double[])` scatters bulk `evaluator.eval()` |
| Maths source | each term's own `eval()`/`der()` | shared `Evaluator` over struct-of-arrays |
| Derivative handler | `onDer(Variable, value, …)` | `onDer(int column, int row, value, …)` |
| Add a term | `equation.addTerm(term)` | `termArray.addTerm(eqElementNum, termElementNum)` |
| Column / row | per-object `getColumn()` / shared `Variable.getRow()` | contiguous block / shared `Variable.getRow()` |
| Activation | per-object `setActive` | per-index `elementActive[]` / `termActive[]` |
| Extra terms | only `SingleEquationTerm`s | term arrays **plus** optional per-element `SingleEquationTerm`s |
| Built by | `AcEquationSystemCreator` | `AcVectorizedEquationSystemCreator` |
