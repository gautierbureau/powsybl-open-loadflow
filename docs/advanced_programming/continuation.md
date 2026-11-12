# Continuation power flow (voltage collapse)

```{warning}
This is an experimental, programmatic-only feature (Java API). It is not wired into the `LoadFlow` API and its
API may change. It lives in the package `com.powsybl.openloadflow.ac.continuation`.
```

A continuation power flow (CPF) traces the P–V "nose" curve of a network: it increases the load by a scalar
factor `lambda` and follows how the bus voltages evolve, up to the **maximum loadability point** (the nose of
the curve), which is the **voltage collapse** point. The distance from the base case to the nose is the
**loadability margin**.

Open Load Flow provides two engines, both reachable through a single entry point, `ContinuationAnalysis`.

## Quick start

```java
ContinuationAnalysisParameters parameters = new ContinuationAnalysisParameters()
        .setEngine(ContinuationAnalysisParameters.Engine.PREDICTOR_CORRECTOR)
        .setLoadIncreaseDirection(LoadIncreaseDirection.allLoads());

ContinuationResult result = ContinuationAnalysis.run(network, parameters);

double margin = result.getMaxLoadFactor(); // loadability margin (lambda at the nose)
result.getNosePoint().ifPresent(nose ->
        System.out.println("collapse at lambda=" + nose.loadFactor()
                + ", min voltage=" + nose.minVoltage() + " pu"));
result.getCriticalBusId().ifPresent(busId ->
        System.out.println("collapse driven by bus " + busId));
```

The overload `ContinuationAnalysis.run(network, lfParameters, lfParametersExt, matrixFactory, parameters)` lets
you control the load flow configuration used for the underlying AC solves. The network state is not modified.

## The two engines

`ContinuationAnalysisParameters.Engine` selects the engine:

| | `STEPPED` (`ContinuationPowerFlow`) | `PREDICTOR_CORRECTOR` (`PredictorCorrectorContinuationPowerFlow`) |
|---|---|---|
| Method | Adaptive load steps, each a full AC load flow | Tangent predictor + Newton corrector on a bordered system |
| Outer loops (distributed slack, reactive limits, taps, ...) | Reused as-is | Not applied (smooth model, single slack) |
| Passes the nose | No (stops at the nose) | Yes (traces the lower/unstable branch) |
| Cost | One AC load flow per point | Two sparse back-substitutions per corrector iteration |
| Use it for | The *practical* collapse point, honouring all controls | The *full* P–V curve, the tangent and participation factors |

The two are complementary: the stepped engine gives the operationally realistic margin (it keeps all the
discrete controls), while the predictor–corrector gives the complete, differentiable curve.

## Load and generation directions

The load increase direction chooses **which loads grow and by how much**:

```java
LoadIncreaseDirection.allLoads();                 // every load, proportionally to its base value
LoadIncreaseDirection.ofLoadIds(Set.of("l1"));    // only these loads
LoadIncreaseDirection.ofBusIds(Set.of("b2"));     // all loads of these buses (stress a zone)
LoadIncreaseDirection.weighted(Map.of("l1", 2.0));// explicit per-load weights
```

For the predictor–corrector engine, generation can pick up the increase instead of leaving it all to the slack
bus (this moves the collapse point). Set a `GenerationParticipation`:

```java
new ContinuationAnalysisParameters()
        .setGenerationParticipation(GenerationParticipation.ofGeneratorIds(Set.of("g2", "g3")));
```

## Reactive-limit breakpoints (predictor–corrector)

By default the predictor–corrector engine keeps every voltage-controlled generator on voltage control. Enabling
reactive limits makes a generator switch from PV to PQ when it reaches its reactive limit, which introduces a
breakpoint on the curve and usually brings the collapse point much closer:

```java
PredictorCorrectorParameters pc = new PredictorCorrectorParameters()
        .setEnforceReactiveLimits(true);
ContinuationResult result = ContinuationAnalysis.run(network, parameters.setPredictorCorrectorParameters(pc));

result.getReactiveLimitBreakpoints().forEach(bp ->
        System.out.println("bus " + bp.busId() + " hit "
                + (bp.maxLimit() ? "max" : "min") + " Q at lambda=" + bp.loadFactor()));
```

The network must be loaded with reactive limits available for this to have an effect.

## Result

`ContinuationResult` exposes:

- `getStatus()` — `NOSE_POINT_REACHED`, `MAX_STEPS_REACHED`, `BASE_CASE_NOT_CONVERGED` or `NO_PARTICIPATING_LOAD`.
- `getMaxLoadFactor()` — the loadability margin (`lambda` at the nose).
- `getNosePoint()` — the collapse point (`ContinuationPoint`: load factor, participating load in MW, minimum
  voltage and its bus).
- `getPoints()` — every traced point, in order; each has a `stable()` flag (upper vs lower branch).
- `getCriticalBusId()` and `getTangentParticipationByBus()` — the collapse-driving bus and, for the
  predictor–corrector engine, the normalized voltage participation factors from the tangent at the nose.
- `getPvCurve(busId)` / `getMonitoredBusIds()` — the per-bus P–V curve as `PvCurvePoint`s (load factor, voltage,
  `dV/dlambda`, stable), when bus voltage recording is on (the default). `dV/dlambda` diverges towards the nose,
  which makes it a collapse-proximity indicator.
- `getReactiveLimitBreakpoints()` — the PV→PQ switches, when reactive limits are enforced.

## Limitations

- The predictor–corrector engine uses a single-slack, smooth model; discrete controls other than reactive
  limits (distributed slack, tap changers, ...) are not applied along the trace.
- Reactive-limit switching occurs at the point where the violation is detected (no bisection to the exact
  crossing yet) and does not switch back PQ→PV on the lower branch.
- The feature is programmatic-only and not part of the `LoadFlow` API.
```
