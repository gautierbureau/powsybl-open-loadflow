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

## Backlog

### 1. Smooth generator participation
Today the single slack bus absorbs the whole load increase in the predictor–corrector engine. Add a smooth
generation-increase direction: scale participating generators' target P by `(1 + λ·k_G)` alongside the loads.
Since `F_λ` is computed by finite-differencing the target vector, generator scaling is picked up automatically
once the mutation step scales generators too. Deliverables:
- a `GenerationParticipation` direction (by generator id / by participation factor), analogous to
  `LoadIncreaseDirection`;
- keep the balance well-posed (slack still closes the residual);
- a test where generation participation changes the nose location.

### 2. Reactive-limit breakpoints on the curve
The smooth predictor–corrector deliberately ignores reactive limits. Real P–V curves have breakpoints where a
generator hits Q_max/Q_min and switches PV→PQ. Add discrete event handling to the continuation:
- monitor each PV generator's reactive power along the curve;
- when a limit is crossed between two points, bisect λ to the crossing, switch the bus PV→PQ (change the
  equation type / structure), and resume the continuation;
- mark breakpoints in the result. Note: structure changes rebuild `F_λ` and the equation index, so this needs
  care around the constant-`F_λ` assumption.

### 3. Public API + docs
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
