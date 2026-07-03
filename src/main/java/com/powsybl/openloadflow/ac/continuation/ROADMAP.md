# Continuation power flow — roadmap

Tracking the planned work for the voltage-collapse / continuation power flow prototype in this package.

## Done

- **Stepped continuation** (`ContinuationPowerFlow`) — adaptive load-factor stepping that reuses the standard
  AC engine and all its outer loops (distributed slack, reactive limits). Traces the upper/stable branch and
  the loadability margin. Cannot pass the nose.
- **Predictor–corrector continuation** (`PredictorCorrectorContinuationPowerFlow`) — true CPF that passes
  through the nose and traces the lower/unstable branch, using local parameterization and an augmented system.
  Smooth, single-slack model (no discrete outer-loop controls during the trace). Reports the nose point and
  tangent-based voltage participation factors (critical bus).
- **Sparse bordered solve** — the augmented `(n+1)` system is solved by block elimination reusing the existing
  sparse `JacobianMatrix` factorization of `F_x` (two back-substitutions per iteration, no dense matrix).
- **Full per-bus voltages per point + dV/dλ** — every bus voltage is recorded at each `ContinuationPoint`
  (opt-in `recordBusVoltages`, on by default), with a finite-difference `dV/dλ` per bus. `ContinuationResult`
  exposes `getMonitoredBusIds()` and `getPvCurve(busId)` returning plottable `PvCurvePoint`s. Works for both
  engines; `dV/dλ` diverges towards the nose (collapse proximity indicator).
- **Smooth generator participation** — the predictor–corrector engine takes an optional `GenerationParticipation`
  (by generator id / weights / all / none) so participating generators ramp their target P by `(1 + λ·k_G)`
  alongside the loads; the slack closes the residual, and `F_λ` picks the scaling up automatically. On a Eurostag
  variant with a generator at the load bus, participation moves the nose from λ≈1.09 to λ≈1.40.
- **Reactive-limit breakpoints** — optional (`setEnforceReactiveLimits`) PV→PQ switching in the
  predictor–corrector engine. After each converged point, voltage-controlled generator buses (except the slack)
  are checked against their reactive limits; a violating bus is frozen at its Q limit (via OLF's
  `freezeGenerationTargetQAndDisableGeneratorVoltageControl`, which swaps `BUS_TARGET_V`→`BUS_TARGET_Q`), `F_λ` is
  recomputed, and the point is re-converged at fixed λ. Breakpoints are reported in `ContinuationResult`. On a
  two-bus test, the switch drops the nose from λ≈12.6 to λ≈3.2. Follow-ups: exact bisection to the crossing
  (currently switches at the detecting point), and PQ→PV switch-back on the lower branch.

## Backlog

### 1. Public API + docs
Expose both engines through the standard OpenLoadFlow surface instead of the current programmatic-only entry:
- an `OpenLoadFlowParameters` extension (or a dedicated `ContinuationPowerFlow` runner) selecting engine
  (stepped / predictor–corrector), the load & generation directions, and stepping parameters;
- a result object aligned with powsybl conventions;
- a `docs/` page (P–V curve concept, the two engines and when to use each, parameters, examples).

## Notes / known tradeoffs

- **Bordering vs augmented factorization near the nose.** The sparse bordered solve relies on `F_x` being
  non-singular; it is singular exactly at the nose. Continuation points straddle the nose so this is fine in
  practice, and a singular solve is caught and turned into a step reduction. If a network ever stalls exactly at
  collapse, the fallback is to factor the augmented `(n+1)` matrix sparsely (non-singular at the nose, but
  re-factored each iteration).
- **Two complementary engines.** The stepped engine gives the *practical* collapse point (with reactive limits
  and slack distribution); the predictor–corrector gives the *full smooth* curve. Item 3 aims to narrow the gap.
