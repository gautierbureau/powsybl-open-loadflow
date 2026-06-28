(part:remedial)=

(ch:curative)=

# The curative layer: operator strategies
the security analysis stopped at the post-contingency state and its violations. An operator does not stop there: faced with a post-contingency overload, they take *remedial action* --- re-dispatch a generator, switch a busbar, move a phase-shifter tap --- and the system settles into a third state. A *security analysis with operator strategies* simulates exactly this: for each contingency, *if* a stated condition is met, it applies a list of actions and solves once more, producing a *curative* state and its own violation report. This part adds that layer on top of the base analysis of the security analysis; it changes none of the base machinery, it *wraps* it.

## Three states, one timeline

Each contingency now generates up to three network states, in strict causal order: 

$$
\underbrace{N}_{\text{pre-contingency}}
  \;\xrightarrow{\;\text{apply contingency }c\;}\;
  \underbrace{N\!-\!k}_{\text{post-contingency}}
  \;\xrightarrow{\;\text{apply actions }\mathcal A\;}\;
  \underbrace{C}_{\text{curative}} .
$$ (eq:threestates)

 The crucial modelling convention is that *actions are relative to the post-contingency state* $N-k$, not to the base case: a "$+20$ MW" generator action adds $20$ MW to whatever that unit was producing *after* the contingency was balanced (Chapter {ref}`ch:contingency`, §{ref}`sec:salack`), and a "$+1$ tap" action steps up from the tap the unit held in $N-k$. The curative state is then re-balanced in its own right. This three-link chain is the load-flow reading of the operator's real timeline.

## What an operator strategy is

:::{admonition} Definition - Operator strategy
:class: note
:name: def:opstrat
An operator strategy (`LfOperatorStrategy`, [`network/action/LfOperatorStrategy.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfOperatorStrategy.java)) binds a *contingency context* to one or more *conditional-action blocks*. Each block is a pair 

$$
\bigl(\text{condition},\;[\,a_1,a_2,\dots,a_p\,]\bigr),
$$

 where the *condition* is a Boolean test on the post-contingency state and the $a_i$ are remedial actions (Chapter {ref}`ch:actions`). A strategy fires the action list of every block whose condition evaluates true.

:::

The conditions answer "is this remedial action warranted?" and come in two families (`checkCondition`, [`sa/AbstractSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java)):

Violation-set conditions

:   tests on the post-contingency *violation list*:

    - `TrueCondition` --- always fire (unconditional remedial action);

    - `AnyViolationCondition` --- fire if *any* monitored equipment is in violation;

    - `AtLeastOneViolationCondition` --- fire if at least one of a named set of equipments is violated;

    - `AllViolationCondition` --- fire only if *all* of a named set are simultaneously violated.

Threshold conditions

:   tests on a *computed quantity* ([`sa/ThresholdConditionEvaluator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/ThresholdConditionEvaluator.java)): a branch/3-winding-transformer flow ($P$, $Q$ or $I$, on a chosen side) or an injection ($\texttt{TARGET\_P}$ before slack, or $\texttt{ACTIVE\_POWER}$ after slack) compared to a threshold with one of $\{<,\le,=,\ne,\ge,>\}$. For example "fire if $P_1$ on line `L7` $>1200$ MW".

## The extended loop

The base loop of Definition {ref}`the security-analysis loop <def:saloop>` gains a curative branch ([`sa/AbstractSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java)). For each contingency $c$, after the post-contingency solve and its violation detection:

1.  for each operator strategy attached to $c$, evaluate each conditional-action block's condition against the *post-contingency* violations/state;

2.  collect the actions of the true blocks and apply them *together* (§{ref}`sec:applytogether`, `applyListOfActions`);

3.  solve the *curative* state with the same engine used for $N-k$ (full AC Newton, full DC, or Woodbury --- Chapter {ref}`ch:cufast`);

4.  detect curative violations, now taking the *post-contingency* result as the reference for de-duplication (Chapter {ref}`ch:limits`): the curative report shows what the actions *left unsolved or newly created*;

5.  restore.

### Save/restore discipline.

Correctness again rests on replaying from a clean state. Two cases are distinguished for efficiency ([`sa/AbstractSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java)):

- with a *single* strategy on the contingency, only the generators' target setpoints need snapshotting (`setGeneratorsInitialTargetPToTargetP`), since the strategy runs once and the whole contingency is rolled back afterwards;

- with *several* strategies on the same contingency, a full `NetworkState.save()` is taken after the post-contingency solve and `restore()`d after each strategy, so every strategy starts from the identical $N-k$ state rather than from its predecessor's curative residue.

The strategies for a contingency are therefore mutually independent *what-if* branches off the same post-contingency state, exactly mirroring an operator comparing alternative remedies.

:::{admonition} Remark - Reference chaining
:class: seealso
The de-duplication reference walks the timeline: post-contingency violations are filtered against $N$, curative violations against $N-k$. An overload present in $N-k$ and *cleared* by the actions simply disappears from the curative report; one the actions fail to clear, or aggravate, remains --- which is precisely the operator's success criterion.

:::
