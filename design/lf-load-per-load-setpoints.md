# Scoping — Per‑load active power setpoints on `LfLoad`

**Status:** Proposal. Nothing below is implemented.
**Scope:** the `LfLoad` / `LfLoadImpl` model in powsybl‑open‑loadflow, and the `p0`‑derived state
that is currently computed once at network build time. Motivated by the time‑series load flow
(`com.powsybl.openloadflow.ts`, PR #28) needing loads as controllable injections, but the defect it
addresses predates that work and is independent of it.
**Author:** Gautier Bureau

> **One‑line summary.** `LfLoad` is a per‑bus aggregate whose `p0`‑derived state is frozen at build
> time. Any caller that wants to change a load's active power *without rebuilding the network* can
> today move the aggregate's `targetP`, but cannot keep the rest of the `p0`‑derived state
> consistent — so the network silently stops being equivalent to one built from the new `p0`. OLF's
> existing incremental caller (`NetworkCache`) sidesteps this by **refusing** the update and
> rebuilding. This note proposes giving `LfLoad` per‑original‑load setpoints so the derived state can
> be maintained instead.

---

## 1. What works today

The mechanical path is already there, and it mirrors generators closely:

| Need | Status |
| --- | --- |
| Resolve an IIDM load id to an LF object | ✅ `LfNetwork.getLoadById(id)` (`LfNetwork.java:360`) |
| Change the target without rebuilding the equation system | ✅ `LfLoad.setTargetP` (`LfLoadImpl.java:128`) fires `onLoadActivePowerTargetChange` → `TargetVector.invalidateValues()` (`TargetVector.java:43‑51`) |
| Save/restore the target across runs | ✅ `BusDcState.LoadDcState` saves `targetP`, `absVariableTargetP`, disabling status |
| Per‑original‑load identity inside the aggregate | ✅ `loadsRefs`, `loadsAbsVariableTargetP` (`LfLoadImpl.java:33,41`) |

So the model is **already half‑way** to per‑load setpoints. Notably, the aggregation is not
load‑bearing for the solver: the equations consume `LfBus.getLoadTargetP()`, which is *itself* a
lazily‑cached sum over `List<LfLoad>`, invalidated on change (`AbstractLfBus.java:479‑492`).

One caveat shapes the proposal: **`LfLoad.targetP` cannot simply be derived from per‑load values.**
It also accumulates LCC converter stations (`LfLoadImpl.java:118`) and boundary lines (`:124`),
neither of which is an IIDM `Load` or appears in `loadsRefs`. The scalar therefore stays
authoritative, with per‑load values maintained alongside it covering the `Load` contributions only.
(Note in passing that `add(BoundaryLine)` updates `targetP` but *not* `initialTargetP`, unlike the
`Load` and LCC paths — an asymmetry that looks unintentional, but is out of scope here.)

## 2. What does not work

**`LfLoad` is a per‑bus, per‑load‑model aggregate, not a load.** One `LfLoadImpl` sums many IIDM
loads — plus LCC converter stations and boundary lines — into one scalar `targetP` at build time
(`LfLoadImpl.java:90‑126`). `getLoadById` returns the aggregate *containing* the load. So
`setTargetP` on it means "set the whole bus's load", and a caller wanting to move one load must
compute `aggregate − old + new` itself, tracking `old` in its own bookkeeping because the model does
not expose it.

**More importantly, `targetP` is not the only thing derived from `p0`.** The rest is computed once at
build time and never revisited.

## 3. The principle

For any caller that mutates a load's active power in place, the correctness criterion is:

> The network must remain equivalent to one **built from scratch** with the new `p0`.

That criterion mechanically enumerates the work: **every quantity the loader derives from `p0` must
be recomputed when `p0` changes.** Inventory:

| Derived quantity | Where | Depends on `p0`? |
| --- | --- | --- |
| `targetP` | `LfLoadImpl.java:95` | Yes |
| `initialTargetP` | `LfLoadImpl.java:96` | Yes |
| `absVariableTargetP`, `loadsAbsVariableTargetP` | `LfLoadImpl.java:109‑111,172‑183` | **Only in `PROPORTIONAL_TO_LOAD`** — see below |
| `ensurePowerFactorConstantByLoad` | `LfLoadImpl.java:98‑108` | Yes, three ways — `p0 < 0`; `p0 == 0 && q0 != 0`; or `LoadDetail.getFixedActivePower() != p0` |
| `getPowerFactor(load)` = `q0/p0` | `LfLoadImpl.java:268‑270` | Yes — and it re‑reads *live IIDM* `p0` |
| `isLoadNotParticipating` | `LfLoadImpl.java:275‑286` | Yes (fictitious / zero‑P cases) |

Three consequences are worth calling out explicitly, because none is obvious:

**(a) `absVariableTargetP` must track `p0` — but only in one balance mode.** In
`PROPORTIONAL_TO_LOAD`, `varP = load.getP0()`, so a changed `p0` changes the slack participation
factor. In `PROPORTIONAL_TO_CONFORM_LOAD`, `varP = LoadDetail.getVariableActivePower()` — a *separate
IIDM attribute*, unaffected by `p0`. A rebuild would read the same value. So the correct behaviour is
**mode‑dependent**: recompute in `PROPORTIONAL_TO_LOAD`, leave alone in
`PROPORTIONAL_TO_CONFORM_LOAD`. Any implementation that unconditionally recomputes is as wrong as one
that never does.

**(b) `initialTargetP` must move with `targetP`, and is not restorable today.** It is the reference
for the constant‑power‑factor path: `ensurePowerFactorConstant` calls
`calculateNewTargetQ(newLoadTargetP − load.getInitialTargetP())`
(`LoadActivePowerDistributionStep.java:104‑118`). Leaving it at the as‑built `p0` makes that delta
include the caller's own setpoint change rather than just the slack movement. Generators already
handle this — `NetworkState.save` calls `setGeneratorsInitialTargetPToTargetP()` and `BusDcState`
saves `generatorsInitialTargetP`. **There is no load equivalent: `LoadDcState` saves `targetP`,
`absVariableTargetP` and disabling status, but not `initialTargetP`** (`BusDcState.java:26‑44`). So a
caller that mutates load `initialTargetP` cannot restore it.

**(c) `getPowerFactor` reads live IIDM `p0`.** A caller that changes the LF target without writing
back to IIDM leaves this reading the as‑loaded `p0`, so the power factor differs from a rebuild's.

## 4. Absolute or delta?

Load scenarios are usually *expressed* as shifts — shed 10% at a bus, add 50 MW of demand response —
so it is worth being explicit about where that belongs.

**At the model level, absolute is the right primitive and delta adds nothing.** Any shift or scaling
reduces to an absolute value the moment the current one is readable:
`setTargetP(id, getTargetP(id) + delta)`, `setTargetP(id, getInitialTargetP(id) * factor)`. Delta
setters on `LfLoad` would add no capability, and would double the surface on which the `p0`‑derived
state of §3 has to be maintained.

**But that reduction needs accessors the model does not have.** There is today no way to read one
original load's contribution to its aggregate — which is precisely why option B (§6) forces the
caller to track it. So the proposal must add, alongside the setter:

- `double getTargetP(String originalLoadId)`
- `double getInitialTargetP(String originalLoadId)`

These are required for absolute setpoints anyway — you cannot replace a value you cannot read — and
they are what make delta and scaling expressible *without* model support.

**A delta must be relative to the network as built, never cumulative.** For a caller like the
time‑series engine, a shift relative to the *previous* step is not merely undesirable, it is
undefined: steps are partitioned across threads and executed in arbitrary order, each restored to the
base snapshot first. Cumulative semantics would reintroduce exactly the inter‑step coupling the
engine's contract forbids. "Shift" therefore means "shift from the as‑built `p0`", which keeps the
§3 criterion intact — the network still has to match a rebuild at `p0_base + delta`.

### What the time‑series engine needs

**Absolute, and only absolute, from the model.** A plan series value is the load's `p0` at step N,
mirroring the generator plan where a value is the target at step N. That is also the only reading
under which the engine's contract — step N equals an independent `LoadFlow.run` — is expressible
without reference to another step.

Delta and scaling then cost the engine nothing: it restores the base state before every step, so the
as‑built value is in hand at apply time, and a shift plan is `base + delta`, a scaling plan
`base × factor`. Relative setpoints are already a deferred item on the generator side; keeping them
out of `LfLoad` keeps that decision in one place — the `ts` API — instead of splitting it across the
model, where it would have to be right twice.

## 5. Precedent — how OLF handles this today

Two existing callers already meet this wall, and neither solves it:

- **`NetworkCache` refuses.** `updateLfLoadTargetP` (`NetworkCache.java:408‑416`) does exactly the
  naive shift — `newTargetP = getInitialTargetP() + valueShift` — under the comment *"Load active
  power distribution is not handled"*. It gets away with it because the caller above bails out first:
  when the balance type is `PROPORTIONAL_TO_LOAD`/`PROPORTIONAL_TO_CONFORM_LOAD` **and** slack is
  distributed, it returns `unsupportedUpdate` and the network is rebuilt (`NetworkCache.java:479‑484`).
  This is honest, not buggy — but it is an escape hatch, and it is the *opposite* of what a caller
  who cannot afford a rebuild needs.

- **Contingencies/actions accept a known error.** The FIXME at `LfLoadImpl.java:190‑197` states that
  `loadsAbsVariableTargetP` is never updated after a load contingency or action, and that this is
  benign *"for security analysis as the network is never updated. Excepted if
  loadPowerFactorConstant is true, [where] the new targetQ could be wrong"*. So there is one
  acknowledged live defect today, narrow in scope.

**The gap this note addresses:** the time‑series engine is the first caller that needs incremental
load updates *together with* load slack distribution. It cannot take `NetworkCache`'s escape hatch —
avoiding the per‑step rebuild is the entire point of the feature — and its contract is precisely the
criterion in §3 (each step must equal an independent `LoadFlow.run`), so it cannot accept the
contingency path's known error either.

## 6. Options

**A. Per‑original‑load setpoints on `LfLoad` (recommended).** Give `LfLoadImpl` per‑load `targetP`
and `initialTargetP` maps alongside the `loadsAbsVariableTargetP` map it already has, maintained in
lockstep with the authoritative aggregate scalar (§1). Add a distinct **"this load's `p0` is now X"**
operation — separate from the existing aggregate `setTargetP(double)`, which means "slack
distribution moved this aggregate" and must *not* recompute `p0`‑derived state. That separation is
the crux: conflating the two is the bug. The new operation updates the per‑load value, adjusts the
aggregate, fires the existing listener event, and maintains the `p0`‑derived state of §3 in one
place. Add per‑load accessors (§4) and `initialTargetP` to `LoadDcState`.

  *Pros:* the model owns its own invariants, and a caller can state that a load consumes something else
  without having to know what that implies. It also opens the way to three things it does **not** do on
  its own, each of which would change existing behaviour and so deserves its own change: exact per‑load
  write‑back in `updateState` instead of participation‑smeared; retiring the `LfLoadImpl.java:190`
  FIXME, which needs `LfLoadAction` to go through the new operation rather than shift the aggregate;
  and letting `NetworkCache` drop its bail‑out. *Cons:* touches a central class (§7).

**B. Caller‑side bookkeeping.** The time‑series engine tracks each load's contribution itself and
shifts the aggregate by the delta. *Pros:* no core change. *Cons:* it does not actually avoid the
problem — `absVariableTargetP`, `initialTargetP` and `ensurePowerFactorConstantByLoad` still need
model cooperation, and `initialTargetP` still is not restorable. It reimplements state the model
should own, in a place that cannot see `distributedOnConformLoad`. Rejected.

**C. Bus‑level plan series.** Sidestep per‑load identity entirely; the plan addresses buses.
*Pros:* exact, no refactor. *Cons:* LF bus ids are synthetic (`VL_1_0`, not `BUS_1`), so this leaks
LF internals into a public API for users who think in load ids. A permanently worse API to avoid a
one‑time refactor. Rejected.

## 7. Blast radius

`LfLoadImpl`, `AbstractLfBus`, `LoadActivePowerDistributionStep`, `LfContingency`, `LfLoadAction`,
`NetworkCache`, `updateState`, and `BusDcState` — which restores loads **by list index, not by id**
(`BusDcState.java:66‑69`), so it is coupled to the current shape and would need care.

The public `LfLoad` surface can stay source‑compatible: the existing scalar `setTargetP` keeps its
meaning ("set the aggregate"), distributing across the per‑load values by their current proportions —
which is what it effectively means today.

## 8. Open questions

1. Should the scalar `LfLoad.setTargetP` be deprecated in favour of the per‑load form, or kept as the
   aggregate‑level operation? Slack distribution legitimately wants the aggregate form.
2. Is per‑load `targetP` acceptable on the memory/hot‑path budget? The aggregate is cached and
   invalidated on change, so the sum is recomputed only on mutation — but this needs measuring on a
   large network, not asserting.
3. Should `updateState` switch to exact per‑load deltas immediately, or keep participation factors
   until the FIXME is separately addressed? Changing it alters SA outputs for existing users.
4. Does `getPowerFactor` reading live IIDM `p0` (§3c) need addressing in the same change, or is it
   only reachable from write‑back paths?

## 9. Test plan

- A bus with several loads: set one load's target, assert the others' targets and the bus aggregate
  are exactly as a rebuild from the new `p0` — this is the test the current model cannot pass.
- The same in both `PROPORTIONAL_TO_LOAD` and `PROPORTIONAL_TO_CONFORM_LOAD`, asserting
  `absVariableTargetP` tracks `p0` in the former and *does not* in the latter (§3a).
- `loadPowerFactorConstant` on, with a load action: the case the `LfLoadImpl.java:190` FIXME says is
  wrong today. It should fail before the change and pass after.
- Save/restore round‑trip covering `initialTargetP` (§3b).
- A `NetworkCache` case with load slack distribution on, asserting the incremental path now matches a
  rebuild instead of declining.
